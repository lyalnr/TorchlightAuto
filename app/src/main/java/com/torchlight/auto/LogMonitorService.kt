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
        private val PATTERN = Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)")
        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
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
            .setContentTitle("日志监控")
            .setContentText("正在抓取系统日志...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                if (!Shizuku.pingBinder()) {
                    sendDebug("错误: Shizuku 未连接")
                    return@Thread
                }

                sendDebug("正在启动 logcat 抓取...")

                // 执行 logcat 并过滤关键词
                val process = execShell(
                    "logcat -v threadtime | grep -iE 'pickup|drop|item|获得|掉落|拾取|AddItem|ItemID|奖励|战利品|物品|通货|装备|传奇|稀有|史诗'"
                ) ?: return@Thread

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var count = 0

                while (running && reader.readLine().also { line = it } != null) {
                    count++
                    val trimmed = line!!.trim()
                    if (trimmed.isNotEmpty()) {
                        sendDebug("[$count] $trimmed")
                        processLine(trimmed)
                    }
                }

                reader.close()
                sendDebug("logcat 读取结束，共 $count 行")

            } catch (e: Exception) {
                sendDebug("异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        logcatThread?.start()
    }

    private fun execShell(command: String): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (e: Exception) {
            sendDebug("Shizuku执行失败: ${e.message}")
            null
        }
    }

    private fun processLine(line: String) {
        val matchResult = PATTERN.find(line)
        if (matchResult != null) {
            val itemName = matchResult.groupValues[1]
            val quantity = matchResult.groupValues[2].toIntOrNull() ?: 0
            val entry = LogEntry(
                timestamp = System.currentTimeMillis(),
                item = itemName,
                quantity = quantity,
                fireValue = 0,
                rawLine = line
            )
            val intent = Intent("LOG_ENTRY")
            intent.putExtra("entry", entry)
            sendBroadcast(intent)
        }
    }

    private fun sendDebug(msg: String) {
        Log.d("LogMonitor", msg)
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            item = "[调试] $msg",
            quantity = 0,
            fireValue = 0,
            rawLine = msg
        )
        val intent = Intent("LOG_ENTRY")
        intent.putExtra("entry", entry)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        logcatThread?.interrupt()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
