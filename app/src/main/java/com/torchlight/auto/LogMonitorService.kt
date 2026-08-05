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
import java.io.InputStreamReader

class LogMonitorService : Service() {
    private var logcatThread: Thread? = null
    private var running = false

    companion object {
        private val DROP_PATTERN = Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)")
        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_LOG_ENTRY = "com.torchlight.auto.LOG_ENTRY"
        
        private val KEYWORDS = listOf(
            "pickup", "drop", "item", "获得", "掉落", "拾取",
            "additem", "itemid", "奖励", "战利品", "物品",
            "通货", "装备", "传奇", "稀有", "史诗"
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startLogcatMonitor()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "日志监控服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("火炬之光掉落监控")
            .setContentText("正在后台运行...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                if (!Shizuku.pingBinder()) {
                    sendDebug("错误: Shizuku 未连接，请检查 Shizuku 是否已启动并授权")
                    return@Thread
                }

                sendDebug("正在启动 logcat...")
                
                val process = Shizuku.newProcess(
                    arrayOf("logcat", "-v", "threadtime"),
                    null, null
                ) ?: run {
                    sendDebug("错误: 无法创建 logcat 进程")
                    return@Thread
                }

                sendDebug("logcat 已启动，等待游戏日志...")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var count = 0

                while (running) {
                    line = try {
                        reader.readLine()
                    } catch (e: Exception) {
                        sendDebug("读取中断: ${e.message}")
                        break
                    }
                    
                    if (line == null) break
                    
                    count++
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (containsKeyword(trimmed)) {
                        sendDebug("[$count] $trimmed")
                        processLine(trimmed)
                    }
                }

                try { reader.close() } catch (_: Exception) {}
                sendDebug("监控结束，共处理 $count 行")

            } catch (e: Exception) {
                sendDebug("严重异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("LogMonitor", "Service crash", e)
            }
        }
        logcatThread?.start()
    }

    private fun containsKeyword(line: String): Boolean {
        return KEYWORDS.any { line.contains(it, ignoreCase = true) }
    }

    private fun processLine(line: String) {
        try {
            val match = DROP_PATTERN.find(line)
            if (match != null) {
                val itemName = match.groupValues[1]
                val quantity = match.groupValues[2].toIntOrNull() ?: 1
                sendEntry(LogEntry(
                    timestamp = System.currentTimeMillis(),
                    item = itemName,
                    quantity = quantity,
                    fireValue = 0,
                    rawLine = line
                ))
            }
        } catch (e: Exception) {
            Log.e("LogMonitor", "processLine error", e)
        }
    }

    private fun sendEntry(entry: LogEntry) {
        try {
            sendBroadcast(Intent(ACTION_LOG_ENTRY).putExtra("entry", entry))
        } catch (e: Exception) {
            Log.e("LogMonitor", "Broadcast error", e)
        }
    }

    private fun sendDebug(msg: String) {
        Log.d("LogMonitor", msg)
        sendEntry(LogEntry(
            timestamp = System.currentTimeMillis(),
            item = "[调试] $msg",
            quantity = 0,
            fireValue = 0,
            rawLine = msg
        ))
    }

    override fun onDestroy() {
        running = false
        try {
            logcatThread?.interrupt()
            logcatThread?.join(500)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
