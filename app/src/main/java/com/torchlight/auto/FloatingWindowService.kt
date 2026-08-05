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
        private var lastTotal: Int = 0
        private var lastEntry: LogEntry? = null

        fun updateData(total: Int, entry: LogEntry) {
            lastTotal = total
            lastEntry = entry
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

        // 如果没有布局文件，创建一个简单的 TextView
        val textView = TextView(this)
        textView.text = "总火值: 0"
        textView.setBackgroundColor(0xAA000000.toInt())
        textView.setTextColor(0xFFFFFFFF.toInt())
        textView.setPadding(20, 10, 20, 10)
        floatView = textView

        windowManager.addView(floatView, params)
        updateFloatingView()
    }

    private fun updateFloatingView() {
        (floatView as? TextView)?.text = "总火值: $lastTotal\n${lastEntry?.item ?: ""} x${lastEntry?.quantity ?: 0}"
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
