package com.torchlight.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatView: TextView? = null

    companion object {
        private var lastItem: String = ""
        private var lastQuantity: Int = 0
        private const val CHANNEL_ID = "float_window_channel"
        private const val NOTIFICATION_ID = 1002

        fun updateData(total: Int, entry: LogEntry) {
            lastItem = entry.item
            lastQuantity = entry.quantity
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingWindow()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("掉落监控")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun showFloatingWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 100

        val textView = TextView(this)
        textView.text = "等待掉落..."
        textView.setBackgroundColor(0xCC000000.toInt())
        textView.setTextColor(0xFFFFFFFF.toInt())
        textView.setPadding(30, 15, 30, 15)
        textView.textSize = 14f
        floatView = textView

        try {
            windowManager.addView(floatView, params)
        } catch (e: Exception) {
            // 可能已经存在
        }
    }

    private fun updateFloatingView() {
        val text = if (lastItem.isEmpty() || lastItem.startsWith("[调试]")) {
            "等待掉落..."
        } else {
            "$lastItem x$lastQuantity"
        }
        floatView?.text = text
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateFloatingView()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        floatView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
