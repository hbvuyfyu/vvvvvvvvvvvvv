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
   *   1. Copy libvcam_inject.so  -> /data/local/tmp/libvcam_inject.so
   *   2. Copy source image       -> /data/local/tmp/vcam/source.jpg
   *   3. setprop wrap.<pkg>         LD_PRELOAD=...
   *   4. am force-stop <pkg>
   *   5. auto-relaunch the app (camera calls now intercepted by hook)
   *
   * The hook ONLY intercepts camera HAL; all other hardware calls
   * (sensors, display, etc.) pass through to the real implementation.
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

          // Push library and image
          RootManager.runCommands(
              "mkdir -p $VCAM_DIR",
              "cp '${srcLib.absolutePath}' $INJECT_LIB",
              "chmod 755 $INJECT_LIB"
          )
          RootManager.runCommands(
              "cp '$mediaPath' '$VCAM_DIR/source.jpg' 2>/dev/null || true",
              "chmod 644 '$VCAM_DIR/source.jpg'"
          )
          RootManager.runCommand("printf '%s' '$mediaPath' > $VCAM_DIR/vcam_config")

          // Relax SELinux
          RootManager.runCommand("setenforce 0 2>/dev/null || true")

          // Set wrap property (Zygote reads it on next app start)
          val wrapProp = "wrap.$pkg"
          val propVal  = "LD_PRELOAD=$INJECT_LIB"
          RootManager.runCommand("setprop '$wrapProp' '$propVal'")
          RootManager.runCommand("resetprop '$wrapProp' '$propVal' 2>/dev/null || true")

          val v = RootManager.runCommand("getprop '$wrapProp'")
          Log.d(TAG, "wrap prop verified: '${v.output}'")

          // Force-stop then relaunch
          RootManager.runCommand("am force-stop $pkg")
          Thread.sleep(1500)
          launchApp(pkg)

          return true
      }

      /**
       * Relaunch the target app using multiple strategies.
       * Each strategy is a separate shell command — no complex inline scripts.
       */
      private fun launchApp(pkg: String) {
          // Strategy A: resolve launcher activity via pm, then am start
          val resolved = RootManager.runCommand(
              "pm resolve-activity --brief -a android.intent.action.MAIN" +
              " -c android.intent.category.LAUNCHER $pkg 2>/dev/null | grep '/' | head -1"
          )
          val comp = resolved.output.trim()
          if (comp.contains("/")) {
              val r = RootManager.runCommand("am start -n $comp 2>/dev/null")
              if (r.success) {
                  Log.d(TAG, "Launched via am start -n $comp"); return
              }
          }

          // Strategy B: monkey (fires a LAUNCHER intent for the package)
          val r2 = RootManager.runCommand(
              "monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null"
          )
          if (r2.success) {
              Log.d(TAG, "Launched via monkey"); return
          }

          // Strategy C: cmd package resolve-activity (newer Android versions)
          val resolved2 = RootManager.runCommand(
              "cmd package resolve-activity --brief -a android.intent.action.MAIN" +
              " -c android.intent.category.LAUNCHER $pkg 2>/dev/null | tail -1"
          )
          val comp2 = resolved2.output.trim()
          if (comp2.contains("/")) {
              RootManager.runCommand("am start -n $comp2 2>/dev/null")
              Log.d(TAG, "Launched via cmd package / am start -n $comp2"); return
          }

          Log.w(TAG, "All launch strategies failed for $pkg")
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

          // No target package: v4l2 fallback
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
  