package com.torchlight.auto

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatView: View? = null

    companion object {
        private var lastItem: String = ""
        private var lastQuantity: Int = 0

        fun updateData(total: Int, entry: LogEntry) {
            lastItem = entry.item
            lastQuantity = entry.quantity
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingWindow()
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
        params.x = 100
        params.y = 100

        val textView = TextView(this)
        textView.text = "等待掉落..."
        textView.setBackgroundColor(0xAA000000.toInt())
        textView.setTextColor(0xFFFFFFFF.toInt())
        textView.setPadding(20, 10, 20, 10)
        floatView = textView

        windowManager.addView(floatView, params)
        updateFloatingView()
    }

    private fun updateFloatingView() {
        val text = if (lastItem.isEmpty()) "等待掉落..." else "$lastItem x$lastQuantity"
        (floatView as? TextView)?.text = text
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateFloatingView()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        floatView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
