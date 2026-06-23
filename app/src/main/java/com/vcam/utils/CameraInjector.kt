package com.vcam.utils

  import android.content.Context
  import android.util.Log
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.launch
  import java.io.File

  /**
   * CameraInjector
   *
   * PRIMARY (rooted Android 8+):
   *   1. Copy libvcam_inject.so to /data/local/tmp/
   *   2. Copy source image to /data/local/tmp/vcam/source.jpg
   *   3. setprop wrap.<pkg>  LD_PRELOAD=/data/local/tmp/libvcam_inject.so
   *   4. am force-stop <pkg>  — kill current instance
   *   5. am start <launcher>  — relaunch; camera calls now intercepted by hook
   *
   * The hook ONLY intercepts camera hardware requests; all other hardware
   * calls (sensors, display, etc.) are forwarded to the real system, so
   * the target app runs normally.
   */
  class CameraInjector(
      private val context: Context,
      private val mediaPath: String,
      private val isVideo: Boolean,
      private val targetPackage: String?
  ) {
      companion object {
          private const val TAG        = "CameraInjector"
          private const val VCAM_DIR   = "/data/local/tmp/vcam"
          private const val INJECT_LIB = "/data/local/tmp/libvcam_inject.so"

          init {
              try { System.loadLibrary("vcam_native") }
              catch (e: UnsatisfiedLinkError) { Log.w(TAG, "vcam_native: ${e.message}") }
          }

          @JvmStatic external fun nativeInjectImage(imagePath: String, videoDevice: String): Int
          @JvmStatic external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int
          @JvmStatic external fun nativeStopInjection()
          @JvmStatic external fun nativeCheckDevice(videoDevice: String): Boolean
      }

      private val scope = CoroutineScope(Dispatchers.IO + Job())
      @Volatile private var running = false
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

      // ── PRIMARY: LD_PRELOAD via Android wrap property ─────────────────────

      private fun injectViaLdPreload(pkg: String): Boolean {
          Log.d(TAG, "LD_PRELOAD injection for: $pkg")

          val nativeLibDir = context.applicationInfo.nativeLibraryDir
          val srcLib = File(nativeLibDir, "libvcam_inject.so")
          if (!srcLib.exists()) {
              Log.e(TAG, "libvcam_inject.so not found in $nativeLibDir"); return false
          }

          // Push library + image
          RootManager.runCommands(
              "mkdir -p $VCAM_DIR",
              "cp '${srcLib.absolutePath}' $INJECT_LIB",
              "chmod 755 $INJECT_LIB",
              "chcon u:object_r:system_file:s0 $INJECT_LIB 2>/dev/null || true"
          )
          RootManager.runCommands(
              "cp '$mediaPath' '$VCAM_DIR/source.jpg' 2>/dev/null || true",
              "chmod 644 '$VCAM_DIR/source.jpg'"
          )
          RootManager.runCommand("printf '%s' '$mediaPath' > $VCAM_DIR/vcam_config")

          // Relax SELinux
          RootManager.runCommand("setenforce 0 2>/dev/null || true")

          // Set wrap property
          val wrapProp = "wrap.$pkg"
          val propVal  = "LD_PRELOAD=$INJECT_LIB"
          RootManager.runCommand("setprop '$wrapProp' '$propVal'")
          RootManager.runCommand("resetprop '$wrapProp' '$propVal' 2>/dev/null || true")

          val v = RootManager.runCommand("getprop '$wrapProp'")
          Log.d(TAG, "wrap prop: '${v.output}'")

          // Kill and relaunch
          RootManager.runCommand("am force-stop $pkg")
          Thread.sleep(1500)  // wait until fully stopped
          launchApp(pkg)

          return true
      }

      /** Multi-strategy app launcher */
      private fun launchApp(pkg: String) {
          // Strategy A: am start with launcher intent (most reliable)
          val launchCmd =
              "COMP=$(cmd package resolve-activity --brief -a android.intent.action.MAIN" +
              " -c android.intent.category.LAUNCHER $pkg 2>/dev/null | grep '/' | head -1);" +
              " if [ -n "\$COMP" ]; then am start -n "\$COMP"; else" +
              " monkey -p $pkg -c android.intent.category.LAUNCHER 1; fi"
          val r = RootManager.runCommand(launchCmd)
          Log.d(TAG, "launch result: success=${r.success} out=${r.output}")

          // Fallback: plain monkey
          if (!r.success) {
              RootManager.runCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null")
          }
      }

      private fun cleanupWrapProp(pkg: String) {
          RootManager.runCommand("setprop 'wrap.$pkg' ''   2>/dev/null || true")
          RootManager.runCommand("resetprop 'wrap.$pkg' '' 2>/dev/null || true")
          RootManager.runCommand("setenforce 1             2>/dev/null || true")
      }

      // ── Main flow ─────────────────────────────────────────────────────────

      private suspend fun performInjection() {
          if (!RootManager.isRooted()) { Log.e(TAG, "Root not available"); return }

          if (!targetPackage.isNullOrBlank()) {
              injectViaLdPreload(targetPackage)
              while (running) { delay(2000) }
              return
          }

          // No target package → v4l2 fallback
          tryLoadV4L2Module()
          val devices = RootManager.getVideoDevices()
          if (devices.isEmpty()) { Log.e(TAG, "No v4l2 devices"); return }

          val device = devices.last()
          val ffmpeg = findBinary("ffmpeg")
          if (ffmpeg != null) strategyFfmpegV4L2(ffmpeg, device)
          else               strategyNativeV4L2(device)
      }

      private fun tryLoadV4L2Module() {
          RootManager.runCommands(
              "modprobe v4l2loopback devices=1 video_nr=10 card_label=VCam exclusive_caps=1 2>/dev/null || true",
              "insmod /vendor/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
              "insmod /system/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
              "insmod /system/lib64/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
          )
      }

      private suspend fun strategyFfmpegV4L2(ffmpeg: String, device: String) {
          val cmd = if (isVideo)
              "$ffmpeg -stream_loop -1 -re -i '$mediaPath' -f v4l2 $device"
          else
              "$ffmpeg -re -loop 1 -i '$mediaPath' -vf scale=640:480 -f v4l2 $device"
          val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
          while (running) { delay(500) }
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
  