package com.vcam.service

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.CodeGateActivity
import com.vcam.ui.MainActivity
import com.vcam.utils.CameraInjector
import com.vcam.utils.MediaUtils
import kotlinx.coroutines.*

class VCamService : Service() {

    companion object {
        const val ACTION_START       = "com.vcam.ACTION_START"
        const val ACTION_STOP        = "com.vcam.ACTION_STOP"
        const val EXTRA_MEDIA_URI    = "extra_media_uri"
        const val EXTRA_IS_VIDEO     = "extra_is_video"

        private const val CHANNEL_ID      = "vcam_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var injector: CameraInjector? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                FloatWindowService.ACTION_ROTATE     ->
                    injector?.rotation = intent.getIntExtra("rotation", 0)
                FloatWindowService.ACTION_MIRROR     ->
                    injector?.mirror = intent.getBooleanExtra("mirror", false)
                FloatWindowService.ACTION_STOP_VCAM  -> {
                    stopInjection()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(FloatWindowService.ACTION_ROTATE)
            addAction(FloatWindowService.ACTION_MIRROR)
            addAction(FloatWindowService.ACTION_STOP_VCAM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        stopFloatWindow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uriStr  = intent.getStringExtra(EXTRA_MEDIA_URI) ?: return START_NOT_STICKY
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                val mediaUri = Uri.parse(uriStr)
                startForeground(NOTIFICATION_ID, buildNotification("Starting…", isVideo))
                serviceScope.launch { startInjection(mediaUri, isVideo) }
                if (Settings.canDrawOverlays(this)) startFloatWindow(isVideo)
            }
            ACTION_STOP -> {
                stopInjection()
                stopFloatWindow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ── Injection ─────────────────────────────────────────────────────

    private suspend fun startInjection(mediaUri: Uri, isVideo: Boolean) {
        try {
            val file = MediaUtils.copyUriToFile(
                this, mediaUri,
                if (isVideo) "vcam_input.mp4" else "vcam_input.jpg"
            ) ?: run { updateNotification("Error: cannot read media", isVideo); return }

            injector = CameraInjector(
                context   = this,
                mediaPath = file.absolutePath,
                isVideo   = isVideo
            )
            injector?.start()
            updateNotification("Injection active — open any camera app", isVideo)
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}", isVideo)
        }
    }

    private fun stopInjection() {
        injector?.stop()
        injector = null
    }

    // ── Float window ──────────────────────────────────────────────────

    private fun startFloatWindow(isVideo: Boolean) {
        val i = Intent(this, FloatWindowService::class.java).apply {
            action = FloatWindowService.ACTION_START
            putExtra(FloatWindowService.EXTRA_IS_VIDEO, isVideo)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun stopFloatWindow() {
        startService(Intent(this, FloatWindowService::class.java).apply {
            action = FloatWindowService.ACTION_STOP_FLOAT
        })
    }

    // ── Notifications ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Virtual Cam",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Camera injection service" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String, isVideo: Boolean): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, VCamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vcam_notif)
            .setContentTitle("Virtual Cam — ${if (isVideo) "Video" else "Image"}")
            .setContentText(status)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String, isVideo: Boolean) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(status, isVideo))
    }
}
