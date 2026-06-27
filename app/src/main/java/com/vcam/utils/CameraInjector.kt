package com.vcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.*

/**
 * CameraInjector — system-wide camera replacement for Magisk-rooted Android 13+.
 *
 * Injection layers (all active simultaneously):
 *   1. v4l2loopback kernel module → /dev/videoX → real device, any app sees it.
 *   2. LD_PRELOAD via wrap.cameraserver  → hooks Camera HAL inside cameraserver.
 *   3. LD_PRELOAD via camera HAL provider wraps → belt-and-suspenders.
 *
 * Frame pipeline:
 *   Image → BitmapFactory → applyTransforms → bitmapToYUYV → nativeUpdateYUYVFrame
 *   Video → MediaMetadataRetriever frame loop at ~30fps → same pipeline
 */
class CameraInjector(
    private val context: Context,
    val mediaPath: String,
    val isVideo: Boolean,
    var rotation: Int = 0,   // 0 / 90 / 180 / 270
    var mirror: Boolean = false
) {
    companion object {
        private const val TAG        = "VCam.Injector"
        private const val VCAM_DIR   = "/data/local/tmp/vcam"
        private const val INJECT_LIB = "/data/local/tmp/libvcam_inject.so"

        const val TARGET_W = 1280
        const val TARGET_H = 720

        init {
            try { System.loadLibrary("vcam_native") }
            catch (e: UnsatisfiedLinkError) { Log.w(TAG, "vcam_native not loaded: ${e.message}") }
        }

        @JvmStatic external fun nativeStartFrameLoop(w: Int, h: Int, device: String): Boolean
        @JvmStatic external fun nativeUpdateYUYVFrame(data: ByteArray, w: Int, h: Int)
        @JvmStatic external fun nativeStopInjection()
        @JvmStatic external fun nativeCheckDevice(device: String): Boolean
        // Legacy stubs
        @JvmStatic external fun nativeInjectImage(path: String, device: String): Int
        @JvmStatic external fun nativeInjectVideo(path: String, device: String): Int
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var running = false
    private var job: Job? = null

    fun start()  { running = true;  job = scope.launch { inject() } }
    fun stop()   {
        running = false
        job?.cancel()
        cleanupWrapProps()
        try { nativeStopInjection() } catch (_: Exception) {}
        RootManager.runCommand("setprop ctl.restart cameraserver")
        Log.d(TAG, "Injection stopped")
    }

    // ── Main injection pipeline ───────────────────────────────────────

    private suspend fun inject() {
        Log.d(TAG, "inject() start — isVideo=$isVideo")
        setupLib()
        setupSELinux()          // Magisk 27 / Android 13 compatible
        loadV4L2()
        setupLdPreload()

        val devices = RootManager.getVideoDevices()
        if (devices.isNotEmpty()) {
            val dev = devices.last()
            Log.d(TAG, "v4l2 device: $dev")
            val ok = try { nativeStartFrameLoop(TARGET_W, TARGET_H, dev) }
                     catch (_: Exception) { false }
            if (ok) { streamFrames(); return }
        }
        // Fallback: write only to shared file (LD_PRELOAD layer still reads it)
        streamFrames()
    }

    // ── SELinux for Android 13 + Magisk 27 ───────────────────────────

    private fun setupSELinux() {
        RootManager.runCommands(
            // Attempt permissive — may silently fail but Magisk MagiskHide/Zygisk handles it
            "setenforce 0 2>/dev/null || true",
            // Magisk policy injection: allow camera HAL to read our shared file
            "magiskpolicy --live " +
              "'allow cameraserver tmpfs file { read open getattr }' " +
              "2>/dev/null || true",
            "magiskpolicy --live " +
              "'allow hal_camera_default tmpfs file { read open getattr }' " +
              "2>/dev/null || true",
            "magiskpolicy --live " +
              "'allow cameraserver unlabeled file { read open getattr }' " +
              "2>/dev/null || true",
            // Label our dir so cameraserver can read it
            "chcon -R u:object_r:camera_data_file:s0 $VCAM_DIR 2>/dev/null || true"
        )
    }

    // ── Library setup ─────────────────────────────────────────────────

    private fun setupLib() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val src = java.io.File(nativeDir, "libvcam_inject.so")
        RootManager.runCommands(
            "mkdir -p $VCAM_DIR",
            "chmod 777 $VCAM_DIR"
        )
        if (src.exists()) {
            RootManager.runCommands(
                "cp '${src.absolutePath}' $INJECT_LIB",
                "chmod 755 $INJECT_LIB",
                "chown root:root $INJECT_LIB 2>/dev/null || true",
                "chcon u:object_r:system_file:s0 $INJECT_LIB 2>/dev/null || true"
            )
        }
    }

    // ── LD_PRELOAD via Magisk resetprop ───────────────────────────────

    private fun setupLdPreload() {
        val val_ = "LD_PRELOAD=$INJECT_LIB"
        val targets = listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "wrap.android.hardware.camera.provider@2.7-service",
            "wrap.android.hardware.camera.provider-impl"
        )
        for (prop in targets) {
            // resetprop (Magisk) bypasses system property restrictions on Android 13
            RootManager.runCommands(
                "resetprop '$prop' '$val_' 2>/dev/null || setprop '$prop' '$val_' 2>/dev/null || true"
            )
        }
        // Restart cameraserver for props to take effect
        RootManager.runCommands(
            "setprop ctl.restart cameraserver",
            "sleep 1"
        )
    }

    private fun cleanupWrapProps() {
        val targets = listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "wrap.android.hardware.camera.provider@2.7-service",
            "wrap.android.hardware.camera.provider-impl"
        )
        for (prop in targets) {
            RootManager.runCommands("resetprop --delete '$prop' 2>/dev/null || true")
        }
    }

    // ── v4l2loopback ──────────────────────────────────────────────────

    private fun loadV4L2() {
        RootManager.runCommands(
            "modprobe v4l2loopback devices=1 video_nr=10 " +
              "card_label=VirtualCam exclusive_caps=1 2>/dev/null || true",
            "insmod /vendor/lib/modules/v4l2loopback.ko devices=1 2>/dev/null || true",
            "insmod /system/lib/modules/v4l2loopback.ko  devices=1 2>/dev/null || true",
            "insmod /system/lib64/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
        )
    }

    // ── Frame streaming ───────────────────────────────────────────────

    private suspend fun streamFrames() = withContext(Dispatchers.IO) {
        if (isVideo) streamVideo() else streamImage()
    }

    private suspend fun streamImage() = withContext(Dispatchers.IO) {
        val bmp = loadAndTransform(mediaPath) ?: run {
            Log.e(TAG, "Cannot decode image: $mediaPath"); return@withContext
        }
        val yuyv = bitmapToYUYV(bmp, TARGET_W, TARGET_H)
        bmp.recycle()
        nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
        while (running && isActive) delay(500)
    }

    private suspend fun streamVideo() = withContext(Dispatchers.IO) {
        val ret = MediaMetadataRetriever()
        try {
            ret.setDataSource(mediaPath)
            val dur = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                         ?.toLongOrNull() ?: 5000L
            var pos = 0L
            val interval = 33L
            while (running && isActive) {
                val frame = ret.getFrameAtTime(pos * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val t = applyTransforms(frame)
                    val yuyv = bitmapToYUYV(t, TARGET_W, TARGET_H)
                    if (t !== frame) t.recycle()
                    frame.recycle()
                    nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
                }
                pos = (pos + interval) % dur
                delay(interval)
            }
        } catch (e: Exception) { Log.e(TAG, "Video stream error: ${e.message}") }
        finally { ret.release() }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────

    private fun loadAndTransform(path: String): Bitmap? {
        val raw = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            ?: return null
        return applyTransforms(raw)
    }

    private fun applyTransforms(src: Bitmap): Bitmap {
        if (rotation == 0 && !mirror) return src
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f, src.width / 2f, src.height / 2f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** ARGB Bitmap → packed YUYV ByteArray (2 bytes/pixel) */
    private fun bitmapToYUYV(src: Bitmap, outW: Int, outH: Int): ByteArray {
        val bmp = if (src.width != outW || src.height != outH)
                      Bitmap.createScaledBitmap(src, outW, outH, true)
                  else src
        val px  = IntArray(outW * outH)
        bmp.getPixels(px, 0, outW, 0, 0, outW, outH)
        if (bmp !== src) bmp.recycle()

        val out = ByteArray(outW * outH * 2)
        var i = 0; var p = 0
        while (p < px.size - 1) {
            val p0 = px[p]; val p1 = px[p + 1]
            val r0=(p0 shr 16)and 0xff; val g0=(p0 shr 8)and 0xff; val b0=p0 and 0xff
            val r1=(p1 shr 16)and 0xff; val g1=(p1 shr 8)and 0xff; val b1=p1 and 0xff
            val y0=((66*r0+129*g0+25*b0+128)shr 8)+16
            val y1=((66*r1+129*g1+25*b1+128)shr 8)+16
            val u =((-38*r0-74*g0+112*b0+128)shr 8)+128
            val v =((112*r0-94*g0-18*b0+128)shr 8)+128
            out[i++]=y0.coerceIn(16,235).toByte()
            out[i++]=u .coerceIn(16,240).toByte()
            out[i++]=y1.coerceIn(16,235).toByte()
            out[i++]=v .coerceIn(16,240).toByte()
            p += 2
        }
        return out
    }
}
