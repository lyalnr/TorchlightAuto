package com.torchlight.auto

import android.Manifest
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
    private var logReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var floatView: View? = null
    private var floatTv: TextView? = null

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
            setOnClickListener { stopMonitoring() }
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
        // 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                appendLog("⚠️ 申请通知权限...")
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            } else {
                appendLog("✅ 通知权限已授权")
            }
        }

        // 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            appendLog("⚠️ 需要悬浮窗权限")
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } else {
            appendLog("✅ 悬浮窗权限已授权")
        }

        checkShizuku()
    }

    private fun checkShizuku() {
        try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val pingMethod = shizukuClass.getMethod("pingBinder")
            val connected = pingMethod.invoke(null) as? Boolean ?: false
            
            if (connected) {
                val uidMethod = shizukuClass.getMethod("getUid")
                val uid = uidMethod.invoke(null) as? Int ?: -1
                if (uid > 0) {
                    appendLog("✅ Shizuku 已连接且已授权")
                } else {
                    appendLog("⚠️ Shizuku 已连接但未授权本应用")
                    appendLog("👉 Shizuku → 应用管理 → 日志监控 → 允许")
                }
            } else {
                appendLog("❌ Shizuku 未连接")
                appendLog("👉 打开 Shizuku → 启动服务")
            }
        } catch (e: Exception) {
            appendLog("❌ Shizuku 检查失败: ${e.message}")
        }
    }

    private fun showFloatWindow() {
        if (!Settings.canDrawOverlays(this)) return
        if (floatView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            600, 400,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        floatView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null)
        floatTv = floatView?.findViewById(android.R.id.text1)
        floatTv?.text = "日志监控悬浮窗\n等待日志..."
        floatTv?.setBackgroundColor(0xCC000000.toInt())
        floatTv?.setTextColor(0xFFFFFFFF.toInt())
        floatTv?.setPadding(16, 16, 16, 16)

        wm.addView(floatView, params)
        appendLog("🪟 悬浮窗已显示")
    }

    private fun hideFloatWindow() {
        floatView?.let {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            } catch (_: Exception) {}
            floatView = null
        }
    }

    private fun updateFloatWindow(msg: String) {
        runOnUiThread {
            floatTv?.text = "日志监控\n$msg"
        }
    }

    private fun registerReceivers() {
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val entry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra("entry", LogEntry::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra("entry")
                }
                entry?.let { 
                    val text = "[掉落] ${it.item} x${it.quantity}"
                    appendLog(text)
                    updateFloatWindow(text)
                }
            }
        }

        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra("msg") ?: return
                appendLog(msg)
                if (msg.contains("掉落") || msg.contains("[")) {
                    updateFloatWindow(msg)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter(LogMonitorService.ACTION_LOG_ENTRY),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registerReceiver(debugReceiver, IntentFilter(LogMonitorService.ACTION_DEBUG),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter(LogMonitorService.ACTION_LOG_ENTRY))
            registerReceiver(debugReceiver, IntentFilter(LogMonitorService.ACTION_DEBUG))
        }
        appendLog("📡 广播接收器已注册")
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLogs.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                appendLog("❌ 请先授予通知权限")
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                return
            }
        }

        try {
            val intent = Intent(this, LogMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            appendLog(">>> 服务启动指令已发送")
            showFloatWindow()
        } catch (e: Exception) {
            appendLog(">>> 💥 启动失败: ${e.message}")
            Log.e("MainActivity", "Start error", e)
        }
    }

    private fun stopMonitoring() {
        try {
            stopService(Intent(this, LogMonitorService::class.java))
            appendLog(">>> 服务已停止")
            hideFloatWindow()
        } catch (e: Exception) {
            appendLog(">>> 停止失败: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("✅ 通知权限已授予")
            } else {
                appendLog("❌ 通知权限被拒绝，服务可能会崩溃")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatWindow()
        try { logReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { debugReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
