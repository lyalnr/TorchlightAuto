package com.torchlight.auto

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private var a11yReceiver: BroadcastReceiver? = null
    private var floatView: View? = null
    private var floatTv: TextView? = null

    companion object {
        private const val REQ_POST_NOTIFICATION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        registerReceivers()
        checkPermissions()
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val btnStart = Button(this).apply {
            text = "▶ 开始监控"
            setOnClickListener { startMonitoring() }
        }
        root.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "⏹ 停止监控"
            setOnClickListener { hideFloatWindow() }
        }
        root.addView(btnStop)

        val tvHint = TextView(this).apply {
            text = "📋 日志输出：\n"
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        root.addView(tvHint)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        tvLogs = TextView(this).apply {
            text = "等待启动...\n"
            textSize = 12f
            setTextIsSelectable(true)
        }
        scrollView.addView(tvLogs)
        root.addView(scrollView)

        setContentView(root)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATION)
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    private fun registerReceivers() {
        a11yReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("text") ?: return
                appendLog("[A11y] $text")
                updateFloatWindow(text)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a11yReceiver, IntentFilter(AutoAccessibilityService.ACTION_TEXT),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(a11yReceiver, IntentFilter(AutoAccessibilityService.ACTION_TEXT))
        }
        appendLog("📡 接收器已注册")
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun startMonitoring() {
        if (!isAccessibilityEnabled()) {
            appendLog("❌ 无障碍服务未开启")
            appendLog("👉 正在跳转到系统设置...")
            Toast.makeText(this, "请找到「日志监控」并开启", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            appendLog("❌ 需要悬浮窗权限")
            return
        }

        appendLog("✅ 无障碍服务已开启")
        appendLog("🎮 请切到游戏，捡东西测试...")
        showFloatWindow()
    }

    private fun showFloatWindow() {
        if (floatView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            600, 300, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }

        floatView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null)
        floatTv = floatView?.findViewById(android.R.id.text1)
        floatTv?.text = "无障碍监控中...\n等待掉落..."
        floatTv?.setBackgroundColor(0xCC000000.toInt())
        floatTv?.setTextColor(0xFFFFFFFF.toInt())
        floatTv?.setPadding(16, 16, 16, 16)
        wm.addView(floatView, params)
        appendLog("🪟 悬浮窗已显示")
    }

    private fun hideFloatWindow() {
        floatView?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            floatView = null
        }
    }

    private fun updateFloatWindow(msg: String) {
        runOnUiThread { floatTv?.text = "无障碍捕获\n$msg" }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLogs.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatWindow()
        try { a11yReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
