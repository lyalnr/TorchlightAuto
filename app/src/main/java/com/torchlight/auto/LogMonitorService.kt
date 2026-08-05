package com.torchlight.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LogMonitorService : Service() {
    private var logPath = ""

    companion object {
        private val PATTERN = Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)")
        private val ITEM_PRICE = mapOf("铁矿石" to 5, "铜矿石" to 10, "金币" to 1, "宝石" to 100)
        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "日志监控", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("日志监控").setContentText("运行中").setSmallIcon(android.R.drawable.ic_menu_gallery).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logPath = intent?.getStringExtra("log_path") ?: ""
        if (logPath.isNotEmpty()) {
            Thread { readLogs() }.start()
        } else stopSelf()
        return START_STICKY
    }

    private fun readLogs() {
        while (true) {
            try {
                if (!Shizuku.pingBinder()) { Thread.sleep(5000); continue }
                val process = Shizuku.Su.run(arrayOf("cat", logPath))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                reader.forEachLine { line ->
                    PATTERN.find(line)?.let {
                        val name = it.groupValues[1]
                        val qty = it.groupValues[2].toIntOrNull() ?: 0
                        val fire = (ITEM_PRICE[name] ?: 0) * qty
                        sendBroadcast(Intent("LOG_ENTRY").putExtra("entry", LogEntry(System.currentTimeMillis(), name, qty, fire, line)))
                    }
                }
                reader.close()
                Thread.sleep(3000)
            } catch (e: Exception) { Log.e("LogMonitor", "读取失败", e) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
