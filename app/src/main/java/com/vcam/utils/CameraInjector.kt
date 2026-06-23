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
   * PRIMARY strategy (rooted, Android 8+):
   *   1. Copy libvcam_inject.so → /data/local/tmp/libvcam_inject.so
   *   2. Copy source image      → /data/local/tmp/vcam/source.jpg
   *   3. setprop wrap.<pkg>       "LD_PRELOAD=/data/local/tmp/libvcam_inject.so"
   *   4. am force-stop <pkg>
   *   5. monkey -p <pkg> ... 1   ← AUTOMATICALLY reopens the target app
   *
   * The target app reopens normally; our hook intercepts camera calls inside it.
   *
   * FALLBACK: v4l2loopback + ffmpeg when v4l2 devices are available.
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
              catch (e: UnsatisfiedLinkError) {
                  Log.w(TAG, "vcam_native not loaded: ${e.message}")
              }
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

      // ── Primary: LD_PRELOAD via wrap property ─────────────────────────────

      private fun injectViaLdPreload(pkg: String): Boolean {
          Log.d(TAG, "Setting up LD_PRELOAD injection for: $pkg")

          // 1. Find our hook library inside the APK native lib dir
          val nativeLibDir = context.applicationInfo.nativeLibraryDir
          val srcLib = File(nativeLibDir, "libvcam_inject.so")
          if (!srcLib.exists()) {
              Log.e(TAG, "libvcam_inject.so not found in $nativeLibDir"); return false
          }

          // 2. Push library and image to /data/local/tmp/
          RootManager.runCommands(
              "mkdir -p $VCAM_DIR",
              "cp '${srcLib.absolutePath}' $INJECT_LIB",
              "chmod 755 $INJECT_LIB"
          )
          RootManager.runCommands(
              "cp '$mediaPath' '$VCAM_DIR/source.jpg' 2>/dev/null || true",
              "chmod 644 '$VCAM_DIR/source.jpg'"
          )
          // Write config so the hook library can find the image
          RootManager.runCommand("printf '%s' '$mediaPath' > $VCAM_DIR/vcam_config")

          // 3. Relax SELinux enforcement so LD_PRELOAD is respected
          RootManager.runCommand("setenforce 0 2>/dev/null || true")

          // 4. Set the wrap system property  
          //    Android's Zygote reads "wrap.<package>" and passes it as env vars to the app
          val wrapProp = "wrap.$pkg"
          val propVal  = "LD_PRELOAD=$INJECT_LIB"
          RootManager.runCommand("setprop '$wrapProp' '$propVal'")
          // Try Magisk's resetprop as fallback (works on restricted ROMs)
          RootManager.runCommand("resetprop '$wrapProp' '$propVal' 2>/dev/null || true")

          val verify = RootManager.runCommand("getprop '$wrapProp'")
          Log.d(TAG, "wrap prop = '${verify.output}'")

          // 5. Force-stop the target app (needed so it restarts fresh with our hook)
          RootManager.runCommand("am force-stop $pkg")

          // 6. Wait briefly then RELAUNCH the target app so the user sees it open
          Thread.sleep(800)
          val launched = launchApp(pkg)
          Log.d(TAG, "App relaunched: $launched")

          return true
      }

      /**
       * Launch the target app using multiple strategies.
       * Returns true if at least one strategy likely succeeded.
       */
      private fun launchApp(pkg: String): Boolean {
          // Strategy A: monkey (most reliable, works on all Android versions)
          val r1 = RootManager.runCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null")
          if (r1.success) return true

          // Strategy B: am start with resolved component
          val r2 = RootManager.runCommand(
              "am start -n $(pm resolve-activity --brief -a android.intent.action.MAIN " +
              "-c android.intent.category.LAUNCHER $pkg 2>/dev/null | tail -1) 2>/dev/null"
          )
          if (r2.success) return true

          // Strategy C: pm dump + am start
          val dump = RootManager.runCommand(
              "pm dump $pkg | grep -A2 'android.intent.action.MAIN' | grep 'cmp=' | head -1"
          )
          val cmpLine = dump.output.trim()
          if (cmpLine.isNotEmpty()) {
              val cmp = cmpLine.substringAfter("cmp=").substringBefore(" ").trim()
              if (cmp.isNotEmpty()) {
                  val r3 = RootManager.runCommand("am start -n '$cmp' 2>/dev/null")
                  if (r3.success) return true
              }
          }

          // Strategy D: am start with just the package (Android will pick the launcher)
          val r4 = RootManager.runCommand("am start '$pkg' 2>/dev/null")
          return r4.success
      }

      private fun cleanupWrapProp(pkg: String) {
          RootManager.runCommand("setprop 'wrap.$pkg' ''     2>/dev/null || true")
          RootManager.runCommand("resetprop 'wrap.$pkg' ''   2>/dev/null || true")
          RootManager.runCommand("setenforce 1               2>/dev/null || true")
          Log.d(TAG, "Cleaned up injection for $pkg")
      }

      // ── Main flow ─────────────────────────────────────────────────────────

      private suspend fun performInjection() {
          if (!RootManager.isRooted()) { Log.e(TAG, "Root not available"); return }

          if (!targetPackage.isNullOrBlank()) {
              injectViaLdPreload(targetPackage)
              // Stay alive in the background; hook runs inside the target app
              while (running) { delay(2000) }
              return
          }

          // No target package: fall back to v4l2 (affects all camera apps)
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
          while (running && scope.isActive) { delay(500) }
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
  