package com.vcam.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class CameraInjector(
    private val context: Context,
    private val mediaPath: String,
    private val isVideo: Boolean,
    private val targetPackage: String?
) {

    companion object {
        private const val TAG = "CameraInjector"

        init {
            try {
                System.loadLibrary("vcam_native")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library not loaded: ${e.message}")
            }
        }

        @JvmStatic
        external fun nativeInjectImage(imagePath: String, videoDevice: String): Int

        @JvmStatic
        external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int

        @JvmStatic
        external fun nativeStopInjection(): Unit

        @JvmStatic
        external fun nativeCheckDevice(videoDevice: String): Boolean
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var running = false

    fun start() {
        running = true
        scope.launch {
            performInjection()
        }
    }

    fun stop() {
        running = false
        try {
            nativeStopInjection()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native injection: ${e.message}")
        }
        stopFallback()
    }

    private suspend fun performInjection() {
        // Step 1: Request root
        if (!RootManager.isRooted()) {
            Log.e(TAG, "Root not available")
            return
        }

        // Step 2: Setup target app permissions if specified
        targetPackage?.let { pkg ->
            setupTargetApp(pkg)
        }

        // Step 3: Find or create virtual camera device
        val videoDevice = findOrCreateVirtualCamera()

        // Step 4: Start injection (native or fallback)
        if (videoDevice != null) {
            startNativeInjection(videoDevice)
        } else {
            startFallbackInjection()
        }
    }

    private fun setupTargetApp(packageName: String) {
        // Grant camera permission via root
        RootManager.runCommands(
            "appops set $packageName CAMERA allow 2>/dev/null || true",
            "pm grant $packageName android.permission.CAMERA 2>/dev/null || true"
        )
        Log.d(TAG, "Target app $packageName configured")
    }

    private fun findOrCreateVirtualCamera(): String? {
        // Check for existing v4l2 loopback device
        val existingDevices = RootManager.getVideoDevices()

        // Try to find a suitable device
        if (existingDevices.isNotEmpty()) {
            Log.d(TAG, "Found video devices: $existingDevices")
            return existingDevices.firstOrNull()
        }

        // Try to load v4l2loopback module
        val loaded = RootManager.runCommands(
            "modprobe v4l2loopback devices=1 video_nr=10 card_label='VCam' exclusive_caps=1 2>/dev/null || " +
            "insmod /system/lib/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
        )

        val devicesAfterLoad = RootManager.getVideoDevices()
        return devicesAfterLoad.firstOrNull()
    }

    private suspend fun startNativeInjection(videoDevice: String) {
        Log.d(TAG, "Starting native injection on $videoDevice")
        try {
            if (isVideo) {
                nativeInjectVideo(mediaPath, videoDevice)
            } else {
                nativeInjectImage(mediaPath, videoDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native injection failed: ${e.message}")
            startFallbackInjection()
        }
    }

    private suspend fun startFallbackInjection() {
        Log.d(TAG, "Starting shell-based fallback injection")
        
        // Use emulator's virtual camera configuration via system properties
        RootManager.runCommands(
            "setprop camera.hal.debug 1 2>/dev/null || true"
        )

        if (isVideo) {
            startVideoFallback()
        } else {
            startImageFallback()
        }
    }

    private suspend fun startVideoFallback() {
        // Use ffmpeg if available, or loop frames via shell
        val ffmpegPath = findBinary("ffmpeg")

        while (running) {
            if (ffmpegPath != null) {
                val devices = RootManager.getVideoDevices()
                val device = devices.firstOrNull() ?: "/dev/video0"
                RootManager.runCommand(
                    "$ffmpegPath -re -stream_loop -1 -i '$mediaPath' " +
                    "-f v4l2 -vcodec rawvideo -pix_fmt yuv420p $device 2>/dev/null &"
                )
                delay(5000)
                break
            } else {
                // No ffmpeg, try direct device writing via native layer
                injectFrameViaShell()
                delay(33) // ~30fps
            }
        }
    }

    private fun startImageFallback() {
        // Convert image and write to virtual camera repeatedly
        val file = File(mediaPath)
        if (!file.exists()) return

        val ffmpegPath = findBinary("ffmpeg")
        val devices = RootManager.getVideoDevices()
        val device = devices.firstOrNull() ?: "/dev/video0"

        if (ffmpegPath != null) {
            RootManager.runCommand(
                "$ffmpegPath -re -loop 1 -i '$mediaPath' " +
                "-f v4l2 -vcodec rawvideo -pix_fmt yuv420p $device 2>/dev/null &"
            )
        } else {
            // Direct injection via our native library
            Log.d(TAG, "Using native image injection")
        }
    }

    private fun injectFrameViaShell() {
        // Write frame data directly to device via cat
        RootManager.runCommand("cat '$mediaPath' > /dev/video0 2>/dev/null || true")
    }

    private fun findBinary(name: String): String? {
        val paths = listOf(
            "/system/bin/$name",
            "/system/xbin/$name",
            "/data/local/tmp/$name",
            "/sbin/$name"
        )
        for (path in paths) {
            if (File(path).exists()) return path
        }
        val result = RootManager.runCommand("which $name 2>/dev/null")
        return if (result.success && result.output.isNotBlank()) result.output.trim() else null
    }

    private fun stopFallback() {
        // Kill any background ffmpeg processes
        RootManager.runCommands(
            "pkill -f ffmpeg 2>/dev/null || true",
            "pkill -f vcam 2>/dev/null || true"
        )
        Log.d(TAG, "VCam injection stopped")
    }
}
