package com.vcam.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.MainActivity

/**
 * FloatWindowService — draggable overlay with Rotate / Mirror / Stop.
 * Launched by VCamService after injection starts (requires SYSTEM_ALERT_WINDOW).
 */
class FloatWindowService : Service() {

    companion object {
        const val ACTION_START       = "com.vcam.float.START"
        const val ACTION_STOP_FLOAT  = "com.vcam.float.STOP"
        const val EXTRA_IS_VIDEO     = "float_is_video"

        const val ACTION_ROTATE      = "com.vcam.float.ROTATE"
        const val ACTION_MIRROR      = "com.vcam.float.MIRROR"
        const val ACTION_STOP_VCAM   = "com.vcam.float.STOP_VCAM"

        private const val CHANNEL_ID = "vcam_float_ch"
        private const val NOTIF_ID   = 1002
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var rotation = 0
    private var isMirrored = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                startForeground(NOTIF_ID, buildNotif(isVideo))
                showWindow(isVideo)
            }
            ACTION_STOP_FLOAT -> {
                removeWindow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { removeWindow(); super.onDestroy() }

    // ── Window ────────────────────────────────────────────────────────

    private fun showWindow(isVideo: Boolean) {
        if (floatView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.float_window, null)

        // Labels
        v.findViewById<TextView>(R.id.tv_float_type)?.text =
            if (isVideo) "🎬 Video" else "🖼 Image"

        // Rotate
        v.findViewById<ImageButton>(R.id.btn_float_rotate)?.setOnClickListener {
            rotation = (rotation + 90) % 360
            sendBroadcast(Intent(ACTION_ROTATE).putExtra("rotation", rotation))
            Toast.makeText(this, "${rotation}°", Toast.LENGTH_SHORT).show()
        }

        // Mirror
        val btnMirror = v.findViewById<ImageButton>(R.id.btn_float_mirror)
        btnMirror?.setOnClickListener {
            isMirrored = !isMirrored
            btnMirror.alpha = if (isMirrored) 1f else 0.5f
            sendBroadcast(Intent(ACTION_MIRROR).putExtra("mirror", isMirrored))
        }

        // Stop
        v.findViewById<ImageButton>(R.id.btn_float_stop)?.setOnClickListener {
            sendBroadcast(Intent(ACTION_STOP_VCAM))
            sendBroadcast(Intent(ACTION_STOP_FLOAT))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm; lp = params; floatView = v

        // Drag only on header labels, not on buttons
        val dragger = DragTouch(v, wm, params)
        v.findViewById<TextView>(R.id.tv_float_type)?.setOnTouchListener(dragger)

        wm.addView(v, params)
    }

    private fun removeWindow() {
        try { floatView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatView = null
    }

    // ── Drag ─────────────────────────────────────────────────────────

    private inner class DragTouch(
        private val v: View,
        private val wm: WindowManager,
        private val p: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var ix = 0; private var iy = 0
        private var tx = 0f; private var ty = 0f

        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix=p.x; iy=p.y; tx=e.rawX; ty=e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ix+(e.rawX-tx).toInt(); p.y = iy+(e.rawY-ty).toInt()
                    wm.updateViewLayout(v, p)
                }
            }
            return false
        }
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "VCam Float",
                NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(isVideo: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Virtual Cam — Float Controls")
            .setContentText("${if (isVideo) "Video" else "Image"} injection active")
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).build()
    }
}
