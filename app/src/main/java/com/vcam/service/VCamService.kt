package com.vcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.MainActivity
import com.vcam.utils.CameraInjector
import com.vcam.utils.MediaUtils
import com.vcam.utils.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VCamService : Service() {

    companion object {
        const val ACTION_START = "com.vcam.ACTION_START"
        const val ACTION_STOP = "com.vcam.ACTION_STOP"
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_TARGET_NAME = "extra_target_name"
        const val EXTRA_IS_VIDEO = "extra_is_video"

        private const val CHANNEL_ID = "vcam_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var injector: CameraInjector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mediaUriStr = intent.getStringExtra(EXTRA_MEDIA_URI) ?: return START_NOT_STICKY
                val mediaUri = Uri.parse(mediaUriStr)
                val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                val targetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: targetPackage ?: "All Apps"
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                val notification = buildNotification(targetName, isVideo)
                startForeground(NOTIFICATION_ID, notification)

                serviceScope.launch {
                    startInjection(mediaUri, targetPackage, isVideo)
                }
            }
            ACTION_STOP -> {
                stopInjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun startInjection(mediaUri: Uri, targetPackage: String?, isVideo: Boolean) {
        try {
            val mediaFile = MediaUtils.copyUriToFile(this, mediaUri,
                if (isVideo) "vcam_input.mp4" else "vcam_input.jpg")
                ?: return

            injector = CameraInjector(this, mediaFile.absolutePath, isVideo, targetPackage)
            injector?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopInjection() {
        injector?.stop()
        injector = null
    }

    private fun buildNotification(targetName: String, isVideo: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VCamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaType = if (isVideo) "video" else "image"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Injecting $mediaType → $targetName")
            .setSmallIcon(R.drawable.ic_vcam_notif)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_vcam), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VCam virtual camera service"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopInjection()
        super.onDestroy()
    }
}
