package com.torchlight.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LogAdapter
    private lateinit var totalText: TextView
    private lateinit var pathInput: EditText
    private lateinit var savePathButton: Button
    private lateinit var startStopButton: Button
    private lateinit var floatToggleButton: Button
    private var isMonitoring = false
    private var isFloating = false
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val SHIZUKU_REQUEST_CODE = 101
        const val PREF_NAME = "log_monitor_prefs"
        const val KEY_LOG_PATH = "log_path"
        const val KEY_FLOATING = "floating_enabled"
        const val OVERLAY_REQUEST_CODE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        totalText = findViewById(R.id.totalText)
        pathInput = findViewById(R.id.pathInput)
        savePathButton = findViewById(R.id.savePathButton)
        startStopButton = findViewById(R.id.startStopButton)
        floatToggleButton = findViewById(R.id.floatToggleButton)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val savedPath = prefs.getString(KEY_LOG_PATH, "")
        if (!savedPath.isNullOrEmpty()) {
            pathInput.setText(savedPath)
        }

        adapter = LogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        checkShizukuPermission()
        checkOverlayPermission()

        savePathButton.setOnClickListener {
            val newPath = pathInput.text.toString().trim()
            if (newPath.isNotEmpty()) {
                prefs.edit().putString(KEY_LOG_PATH, newPath).apply()
                Toast.makeText(this, "路径已保存", Toast.LENGTH_SHORT).show()
            }
        }

        startStopButton.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                if (!Shizuku.pingBinder()) {
                    Toast.makeText(this, "Shizuku 未连接", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val path = pathInput.text.toString().trim()
                if (path.isEmpty()) {
                    Toast.makeText(this, "请先输入日志路径", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_LOG_PATH, path).apply()
                startMonitoring(path)
            }
        }

        floatToggleButton.setOnClickListener {
            if (isFloating) {
                stopFloating()
            } else {
                if (checkOverlayPermission()) {
                    startFloating()
                } else {
                    requestOverlayPermission()
                }
            }
        }

        isFloating = prefs.getBoolean(KEY_FLOATING, false)
        floatToggleButton.text = if (isFloating) "关闭悬浮窗" else "开启悬浮窗"
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (checkOverlayPermission()) {
                startFloating()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startFloating() {
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        startService(Intent(this, FloatingWindowService::class.java))
        isFloating = true
        prefs.edit().putBoolean(KEY_FLOATING, true).apply()
        floatToggleButton.text = "关闭悬浮窗"
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloating() {
        stopService(Intent(this, FloatingWindowService::class.java))
        isFloating = false
        prefs.edit().putBoolean(KEY_FLOATING, false).apply()
        floatToggleButton.text = "开启悬浮窗"
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun checkShizukuPermission() {
        if (Shizuku.pingBinder()) {
            if (!Shizuku.checkSelfPermission()) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        }
    }

    private fun startMonitoring(logPath: String) {
        val intent = Intent(this, LogMonitorService::class.java)
        intent.putExtra("log_path", logPath)
        startService(intent)
        isMonitoring = true
        startStopButton.text = "停止监控"
        Toast.makeText(this, "开始监控日志", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, LogMonitorService::class.java))
        isMonitoring = false
        startStopButton.text = "开始监控"
        Toast.makeText(this, "停止监控", Toast.LENGTH_SHORT).show()
    }

    fun updateUI(entry: LogEntry) {
        runOnUiThread {
            adapter.addEntry(entry)
            val total = adapter.getTotalFire()
            totalText.text = "总火值: $total"
            FloatingWindowService.updateData(total, entry)
            if (isFloating) {
                sendBroadcast(Intent("UPDATE_FLOATING"))
            }
        }
    }
}
