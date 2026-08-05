package com.torchlight.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LogMonitorService : Service() {
    private lateinit var fileObserver: FileObserver
    private var lastLineCount = 0L
    private var logPath = ""

    companion object {
        private val PATTERN = Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)")
        private val ITEM_PRICE = mapOf(
            "铁矿石" to 5,
            "铜矿石" to 10,
            "金币" to 1,
            "宝石" to 100
        )
        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { logPath = it.getStringExtra("log_path") ?: "" }
        if (logPath.isNotEmpty()) {
            startMonitoring()
        } else {
            Log.e("LogMonitor", "未收到日志路径，停止服务")
            stopSelf()
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
            .setContentText("正在监控游戏日志...")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
    }

    private fun startMonitoring() {
        val dir = File(logPath).parentFile ?: return
        fileObserver = object : FileObserver(dir.absolutePath, FileObserver.MODIFY or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith("UE_game.log")) {
                    readNewLines()
                }
            }
        }
        fileObserver.startWatching()
        readNewLines()
    }

    private fun readNewLines() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e("LogMonitor", "Shizuku 未连接")
                return
            }

            val totalLines = getLineCount() ?: return
            if (totalLines > lastLineCount) {
                val startLine = lastLineCount + 1
                readLinesFrom(startLine)
                lastLineCount = totalLines
            }
        } catch (e: Exception) {
            Log.e("LogMonitor", "读取日志失败", e)
        }
    }

    private fun getLineCount(): Long? {
        val process = shizukuExec("wc -l < \"$logPath\"") ?: return null
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val result = reader.readText().trim().toLongOrNull()
        reader.close()
        process.waitFor()
        return result
    }

    private fun readLinesFrom(startLine: Long) {
        val process = shizukuExec("tail -n +$startLine \"$logPath\"") ?: return
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            processLine(line!!)
        }
        reader.close()
        process.waitFor()
    }

    /**
     * 使用反射调用 Shizuku.newProcess()，兼容 private 访问限制
     */
    private fun shizukuExec(command: String): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (e: NoSuchMethodException) {
            Log.e("LogMonitor", "Shizuku.newProcess() 不存在，请升级 Shizuku 到 13.x+", e)
            null
        } catch (e: Exception) {
            Log.e("LogMonitor", "Shizuku 执行命令失败: $command", e)
            null
        }
    }

    private fun processLine(line: String) {
        val matchResult = PATTERN.find(line)
        if (matchResult != null) {
            val itemName = matchResult.groupValues[1]
            val quantity = matchResult.groupValues[2].toIntOrNull() ?: 0
            val price = ITEM_PRICE[itemName] ?: 0
            val totalFire = price * quantity
            val entry = LogEntry(
                timestamp = System.currentTimeMillis(),
                item = itemName,
                quantity = quantity,
                fireValue = totalFire,
                rawLine = line
            )
            val intent = Intent("LOG_ENTRY")
            intent.putExtra("entry", entry)
            sendBroadcast(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fileObserver.stopWatching()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
