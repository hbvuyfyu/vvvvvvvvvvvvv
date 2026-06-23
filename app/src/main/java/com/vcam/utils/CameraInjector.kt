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
                  Log.w(TAG, "Native library not loaded (non-fatal): ${e.message}")
              }
          }

          @JvmStatic external fun nativeInjectImage(imagePath: String, videoDevice: String): Int
          @JvmStatic external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int
          @JvmStatic external fun nativeStopInjection(): Unit
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
          try { nativeStopInjection() } catch (_: Exception) {}
          // Kill any background injection processes
          RootManager.runCommands(
              "pkill -f ffmpeg 2>/dev/null || true",
              "pkill -f v4l2 2>/dev/null || true"
          )
          Log.d(TAG, "VCam stopped")
      }

      private suspend fun performInjection() {
          if (!RootManager.isRooted()) {
              Log.e(TAG, "Root not available — cannot inject")
              return
          }

          targetPackage?.let { setupTargetApp(it) }

          // Detect emulator type and choose strategy
          val strategy = detectBestStrategy()
          Log.d(TAG, "Using injection strategy: $strategy")

          when (strategy) {
              Strategy.V4L2_LOOPBACK -> strategyV4L2()
              Strategy.FFMPEG_V4L2   -> strategyFfmpegV4L2()
              Strategy.HAL_BIND_MOUNT -> strategyHalBindMount()
              Strategy.EMULATOR_PROP  -> strategyEmulatorProp()
              Strategy.NATIVE_ONLY    -> strategyNative()
          }
      }

      private enum class Strategy {
          V4L2_LOOPBACK, FFMPEG_V4L2, HAL_BIND_MOUNT, EMULATOR_PROP, NATIVE_ONLY
      }

      private fun detectBestStrategy(): Strategy {
          // 1. Check if v4l2 device already exists (real device or loopback loaded)
          val v4l2Devices = RootManager.getVideoDevices()
          if (v4l2Devices.isNotEmpty()) {
              val ffmpeg = findBinary("ffmpeg")
              return if (ffmpeg != null) Strategy.FFMPEG_V4L2 else Strategy.V4L2_LOOPBACK
          }

          // 2. Try to load v4l2loopback kernel module
          RootManager.runCommands(
              "modprobe v4l2loopback devices=1 video_nr=10 card_label=VCam exclusive_caps=1 2>/dev/null || true",
              "insmod /vendor/lib/modules/v4l2loopback.ko devices=1 2>/dev/null || true",
              "insmod /system/lib/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
          )
          if (RootManager.getVideoDevices().isNotEmpty()) {
              val ffmpeg = findBinary("ffmpeg")
              return if (ffmpeg != null) Strategy.FFMPEG_V4L2 else Strategy.V4L2_LOOPBACK
          }

          // 3. Check for goldfish/ranchu emulator (Android Studio AVD)
          val isGoldfish = File("/system/lib/hw/camera.goldfish.so").exists() ||
                           File("/system/lib64/hw/camera.goldfish.so").exists() ||
                           File("/vendor/lib/hw/camera.ranchu.so").exists() ||
                           File("/vendor/lib64/hw/camera.ranchu.so").exists()
          if (isGoldfish) return Strategy.EMULATOR_PROP

          // 4. Try HAL bind mount (LDPlayer, MuMu, Genymotion)
          return Strategy.HAL_BIND_MOUNT
      }

      /** Strategy 1: Write raw frames to v4l2 loopback device via native library */
      private suspend fun strategyV4L2() {
          val device = RootManager.getVideoDevices().firstOrNull() ?: return
          Log.d(TAG, "V4L2 strategy: writing to $device")
          try {
              // Set permissions on device
              RootManager.runCommand("chmod 666 $device")
              if (isVideo) {
                  nativeInjectVideo(mediaPath, device)
              } else {
                  nativeInjectImage(mediaPath, device)
              }
          } catch (e: Exception) {
              Log.e(TAG, "V4L2 native failed: ${e.message}")
              // Fallback: loop via shell
              while (running) {
                  RootManager.runCommand("cat '$mediaPath' > $device 2>/dev/null || true")
                  delay(33)
              }
          }
      }

      /** Strategy 2: ffmpeg writes frames to v4l2 device (most compatible) */
      private fun strategyFfmpegV4L2() {
          val device = RootManager.getVideoDevices().firstOrNull() ?: return
          val ffmpeg = findBinary("ffmpeg") ?: return
          Log.d(TAG, "ffmpeg strategy: $ffmpeg -> $device")
          RootManager.runCommand("chmod 666 $device 2>/dev/null || true")

          val cmd = if (isVideo) {
              "$ffmpeg -re -stream_loop -1 -i '$mediaPath' -f v4l2 -vcodec rawvideo -pix_fmt yuv420p $device 2>/dev/null &"
          } else {
              "$ffmpeg -re -loop 1 -i '$mediaPath' -f v4l2 -vcodec rawvideo -pix_fmt yuv420p $device 2>/dev/null &"
          }
          RootManager.runCommand(cmd)
      }

      /**
       * Strategy 3: HAL Bind Mount for non-goldfish emulators (LDPlayer, MuMu, Genymotion)
       * Replaces camera HAL with our custom wrapper that reads from a FIFO pipe.
       * Our VCamService writes frames to /data/local/tmp/vcam_fifo.
       */
      private fun strategyHalBindMount() {
          Log.d(TAG, "HAL bind mount strategy")

          // Create FIFO pipe for frame delivery
          val fifoPath = "/data/local/tmp/vcam_fifo"
          RootManager.runCommands(
              "rm -f $fifoPath 2>/dev/null || true",
              "mkfifo $fifoPath",
              "chmod 666 $fifoPath"
          )

          // Write our media file path as a config file that the HAL wrapper reads
          val configPath = "/data/local/tmp/vcam_config"
          RootManager.runCommands(
              "echo '$mediaPath' > $configPath",
              "echo '${if (isVideo) "video" else "image"}' >> $configPath",
              "chmod 644 $configPath"
          )

          // Find camera HAL libraries
          val halPaths = listOf(
              "/vendor/lib/hw/android.hardware.camera.provider@2.7-impl.so",
              "/vendor/lib64/hw/android.hardware.camera.provider@2.7-impl.so",
              "/vendor/lib/hw/camera.default.so",
              "/vendor/lib64/hw/camera.default.so",
              "/system/lib/hw/camera.default.so",
              "/system/lib64/hw/camera.default.so"
          )
          val halPath = halPaths.firstOrNull { File(it).exists() }

          if (halPath != null) {
              Log.d(TAG, "Found camera HAL at $halPath")
              // Remount vendor/system as rw to allow bind mount
              val halDir = File(halPath).parent ?: return
              RootManager.runCommands(
                  "mount -o remount,rw ${halDir.substringBefore("/hw")} 2>/dev/null || true"
              )
          }

          // Push frames via shell using dd loop (works without ffmpeg)
          startShellFrameLoop(fifoPath)
      }

      /**
       * Strategy 4: Goldfish/Ranchu emulator property trick
       * Configure the virtual camera via emulator properties
       */
      private fun strategyEmulatorProp() {
          Log.d(TAG, "Emulator property strategy (goldfish/ranchu)")

          // Write the media file path into shared memory area the emulator camera reads
          val vcamDir = "/data/local/tmp/vcam"
          RootManager.runCommands(
              "mkdir -p $vcamDir",
              "chmod 777 $vcamDir"
          )

          // Copy media to known location
          val destName = if (isVideo) "source.mp4" else "source.jpg"
          val destPath = "$vcamDir/$destName"
          RootManager.runCommand("cp '$mediaPath' $destPath && chmod 644 $destPath")

          // Set emulator camera properties
          RootManager.runCommands(
              "setprop qemu.sf.fake_camera emulated 2>/dev/null || true",
              "setprop persist.camera.input.path $destPath 2>/dev/null || true",
              "setprop debug.camera.forcebus 0 2>/dev/null || true"
          )

          // On goldfish, the webcam video device is /dev/video0 mapped to host
          // Try to write directly using the emulator control socket
          val emulatorSocket = "/dev/socket/qemud"
          if (File(emulatorSocket).exists()) {
              Log.d(TAG, "Found emulator control socket, using it")
              RootManager.runCommand(
                  "echo 'camera set source $destPath' | nc -U $emulatorSocket 2>/dev/null || true"
              )
          }

          // Also try the AVD webcam device if it exists
          val webcamDevices = RootManager.runCommand("ls /dev/video* 2>/dev/null")
          if (webcamDevices.output.isNotBlank()) {
              val device = webcamDevices.output.trim().split("\n").first()
              Log.d(TAG, "Found webcam device $device, injecting frames")
              startShellFrameLoop(device)
          }
      }

      /** Strategy 5: Pure native injection as last resort */
      private suspend fun strategyNative() {
          Log.d(TAG, "Native-only strategy (last resort)")
          // Try all known video device paths
          val devicesToTry = listOf("/dev/video0", "/dev/video1", "/dev/video10")
          for (device in devicesToTry) {
              if (File(device).exists()) {
                  RootManager.runCommand("chmod 666 $device 2>/dev/null || true")
                  try {
                      if (isVideo) nativeInjectVideo(mediaPath, device)
                      else nativeInjectImage(mediaPath, device)
                      return
                  } catch (_: Exception) {}
              }
          }
          Log.e(TAG, "No injection method worked on this emulator")
      }

      /** Push image frames in a loop using shell commands (no ffmpeg needed) */
      private fun startShellFrameLoop(dest: String) {
          scope.launch {
              val file = File(mediaPath)
              if (!file.exists()) return@launch

              Log.d(TAG, "Shell frame loop -> $dest")
              while (running) {
                  if (File(dest).exists()) {
                      // Write frame data to destination
                      RootManager.runCommand("cat '$mediaPath' > '$dest' 2>/dev/null || true")
                  }
                  delay(if (isVideo) 33L else 500L)
              }
          }
      }

      private fun setupTargetApp(packageName: String) {
          RootManager.runCommands(
              "appops set $packageName CAMERA allow 2>/dev/null || true",
              "pm grant $packageName android.permission.CAMERA 2>/dev/null || true"
          )
          Log.d(TAG, "Target app $packageName configured")
      }

      private fun findBinary(name: String): String? {
          val paths = listOf(
              "/system/bin/$name", "/system/xbin/$name",
              "/data/local/tmp/$name", "/sbin/$name",
              "/vendor/bin/$name"
          )
          for (path in paths) { if (File(path).exists()) return path }
          val result = RootManager.runCommand("which $name 2>/dev/null")
          return if (result.success && result.output.isNotBlank()) result.output.trim() else null
      }
  }
  