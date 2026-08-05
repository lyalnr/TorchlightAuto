package com.torchlight.auto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        requestPermissions()
        registerReceivers()
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
            text = "📋 日志输出："
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

    private fun requestPermissions() {
        // Android 13+ 必须申请通知权限，否则 startForeground 会崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        // 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
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
                entry?.let { appendLog("[掉落] ${it.item} x${it.quantity}") }
            }
        }

        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra("msg") ?: return
                appendLog(msg)
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
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLogs.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startMonitoring() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }

        // Android 13+ 检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请先授予通知权限", Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) {
            appendLog(">>> 启动失败: ${e.message}")
            Log.e("MainActivity", "Start service error", e)
        }
    }

    private fun stopMonitoring() {
        try {
            stopService(Intent(this, LogMonitorService::class.java))
            appendLog(">>> 服务已停止")
        } catch (e: Exception) {
            appendLog(">>> 停止失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { logReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { debugReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
