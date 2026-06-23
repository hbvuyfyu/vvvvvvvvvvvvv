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

  /**
   * CameraInjector — injects a JPEG/video into the target app's camera feed.
   *
   * PRIMARY strategy (Android 8+, rooted):
   *   1. Copy libvcam_inject.so to /data/local/tmp/
   *   2. Push source image to /data/local/tmp/vcam/source.jpg
   *   3. setprop wrap.<pkg> "LD_PRELOAD=/data/local/tmp/libvcam_inject.so"
   *   4. am force-stop <pkg>  →  app relaunches with our hook active
   *
   * The hook library (libvcam_inject.so) overrides hw_get_module_by_class() so
   * Camera1 API calls inside the target process open our fake camera HAL,
   * which delivers frames from /data/local/tmp/vcam/source.jpg.
   *
   * FALLBACK: v4l2loopback + ffmpeg (when v4l2 devices are available).
   */
  class CameraInjector(
      private val context: Context,
      private val mediaPath: String,
      private val isVideo: Boolean,
      private val targetPackage: String?
  ) {
      companion object {
          private const val TAG         = "CameraInjector"
          private const val VCAM_DIR    = "/data/local/tmp/vcam"
          private const val INJECT_LIB  = "/data/local/tmp/libvcam_inject.so"

          init {
              try { System.loadLibrary("vcam_native") }
              catch (e: UnsatisfiedLinkError) {
                  Log.w(TAG, "vcam_native not loaded (non-fatal): ${e.message}")
              }
          }

          @JvmStatic external fun nativeInjectImage(imagePath: String, videoDevice: String): Int
          @JvmStatic external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int
          @JvmStatic external fun nativeStopInjection()
          @JvmStatic external fun nativeCheckDevice(videoDevice: String): Boolean
      }

      private val scope = CoroutineScope(Dispatchers.IO + Job())
      private var running = false
      private var injectionJob: Job? = null

      fun start() {
          running = true
          injectionJob = scope.launch { performInjection() }
      }

      fun stop() {
          running = false
          injectionJob?.cancel()
          targetPackage?.let { cleanupWrapProp(it) }
          try { nativeStopInjection() } catch (_: Exception) {}
          RootManager.runCommands(
              "pkill -f ffmpeg 2>/dev/null || true",
              "pkill -f v4l2  2>/dev/null || true"
          )
          Log.d(TAG, "VCam stopped")
      }

      // ── Primary: LD_PRELOAD hook ──────────────────────────────────────────

      private fun injectViaLdPreload(pkg: String): Boolean {
          Log.d(TAG, "LD_PRELOAD injection → $pkg")

          // 1. Find libvcam_inject.so inside our APK's native lib directory
          val nativeLibDir = context.applicationInfo.nativeLibraryDir
          val srcLib = File(nativeLibDir, "libvcam_inject.so")
          if (!srcLib.exists()) {
              Log.e(TAG, "libvcam_inject.so not found in $nativeLibDir"); return false
          }

          // 2. Push library to /data/local/tmp/
          RootManager.runCommands(
              "mkdir -p $VCAM_DIR",
              "cp '${srcLib.absolutePath}' $INJECT_LIB",
              "chmod 755 $INJECT_LIB"
          )

          // 3. Push source image and config
          RootManager.runCommands(
              "cp '$mediaPath' '$VCAM_DIR/source.jpg' 2>/dev/null || true",
              "chmod 644 '$VCAM_DIR/source.jpg'"
          )
          RootManager.runCommand("echo '$mediaPath' > $VCAM_DIR/vcam_config")

          // 4. Relax SELinux so LD_PRELOAD is honoured
          RootManager.runCommand("setenforce 0 2>/dev/null || true")

          // 5. Set wrap property — Zygote will LD_PRELOAD our lib on next launch
          val wrapProp = "wrap.$pkg"
          val propVal  = "LD_PRELOAD=$INJECT_LIB"
          RootManager.runCommand("setprop '$wrapProp' '$propVal'")

          // Verify the prop was set
          val verify = RootManager.runCommand("getprop '$wrapProp'")
          Log.d(TAG, "verify: ${verify.output}")
          if (!verify.output.contains("LD_PRELOAD")) {
              Log.w(TAG, "setprop may have been ignored; property not confirmed")
              // On some ROMs the wrap property namespace is restricted; try alternate
              RootManager.runCommand(
                  "resetprop -n '$wrapProp' '$propVal' 2>/dev/null || " +
                  "setprop '$wrapProp' '$propVal'"
              )
          }

          // 6. Force-stop the target app so it picks up the new LD_PRELOAD on relaunch
          RootManager.runCommand("am force-stop $pkg")
          Log.d(TAG, "Target app stopped. User must reopen $pkg — camera feed will be injected.")
          return true
      }

      private fun cleanupWrapProp(pkg: String) {
          val wrapProp = "wrap.$pkg"
          RootManager.runCommand("setprop '$wrapProp' '' 2>/dev/null || true")
          RootManager.runCommand("resetprop -n '$wrapProp' '' 2>/dev/null || true")
          RootManager.runCommand("setenforce 1 2>/dev/null || true")
          Log.d(TAG, "Cleaned up wrap prop for $pkg")
      }

      // ── Main injection flow ───────────────────────────────────────────────

      private suspend fun performInjection() {
          if (!RootManager.isRooted()) {
              Log.e(TAG, "Root not available — cannot inject"); return
          }

          // PRIMARY: LD_PRELOAD hook
          if (!targetPackage.isNullOrBlank()) {
              val ok = injectViaLdPreload(targetPackage)
              if (ok) {
                  // Stay alive; the hook runs inside the target app process
                  while (running) { delay(2000) }
                  return
              }
              Log.w(TAG, "LD_PRELOAD failed — falling back to v4l2")
          }

          // FALLBACK: v4l2 loopback
          tryLoadV4L2Module()
          val devices = RootManager.getVideoDevices()
          if (devices.isEmpty()) {
              Log.e(TAG, "No v4l2 devices and LD_PRELOAD failed — injection impossible")
              return
          }

          val device = devices.last()
          Log.d(TAG, "V4L2 fallback using $device")
          val ffmpeg = findBinary("ffmpeg")
          if (ffmpeg != null) strategyFfmpegV4L2(ffmpeg, device)
          else               strategyNativeV4L2(device)
      }

      private fun tryLoadV4L2Module() {
          RootManager.runCommands(
              "modprobe v4l2loopback devices=1 video_nr=10 card_label=VCam exclusive_caps=1 2>/dev/null || true",
              "insmod /vendor/lib/modules/v4l2loopback.ko devices=1 2>/dev/null || true",
              "insmod /system/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
              "insmod /system/lib64/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
          )
      }

      private suspend fun strategyFfmpegV4L2(ffmpeg: String, device: String) {
          val cmd = if (isVideo)
              "$ffmpeg -stream_loop -1 -re -i '$mediaPath' -f v4l2 $device"
          else
              "$ffmpeg -re -loop 1 -i '$mediaPath' -vf scale=640:480 -f v4l2 $device"
          Log.d(TAG, "ffmpeg cmd: $cmd")
          val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
          while (running && isActive) { delay(500) }
          proc.destroy()
      }

      private suspend fun strategyNativeV4L2(device: String) {
          scope.launch(Dispatchers.IO) {
              if (isVideo) nativeInjectVideo(mediaPath, device)
              else         nativeInjectImage(mediaPath, device)
          }
          while (running) { delay(1000) }
          nativeStopInjection()
      }

      private fun findBinary(name: String): String? =
          listOf("/system/bin", "/system/xbin", "/data/local/tmp", "/sbin", "/vendor/bin")
              .map { File(it, name) }
              .firstOrNull { it.exists() && it.canExecute() }
              ?.absolutePath
  }
  