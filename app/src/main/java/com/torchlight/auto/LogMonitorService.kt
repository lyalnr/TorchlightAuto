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
import java.io.File
import java.io.RandomAccessFile

class LogMonitorService : Service() {

    private lateinit var fileObserver: FileObserver
    private var lastPos = 0L
    private val logFile = File(LOG_PATH)

    companion object {
        // ======== 请修改以下三个常量为你的实际值 ========
        // 1. 日志文件完整路径
        private const val LOG_PATH = "/storage/emulated/0/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"

        // 2. 正则表达式，匹配一行日志中的物品名和数量（示例为“掉落 物品名 x数量”）
        // 如果日志格式不同，修改此正则
        private val PATTERN = Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)")

        // 3. 物品 → 火 换算表（按你的游戏实际填写）
        private val ITEM_PRICE = mapOf(
            "铁矿石" to 5,
            "铜矿石" to 10,
            "金币" to 1,
            "宝石" to 100
            // 添加更多...
        )
        // ==============================================

        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        lastPos = if (logFile.exists()) logFile.length() else 0
        startMonitoring()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "日志监控服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
        val dir = logFile.parentFile ?: return
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
        if (!logFile.exists()) return
        try {
            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(lastPos)
                var line: String?
                while (raf.filePointer < raf.length()) {
                    line = raf.readLine()
                    if (line != null) {
                        processLine(line)
                    }
                }
                lastPos = raf.filePointer
            }
        } catch (e: Exception) {
            Log.e("LogMonitor", "读取日志失败", e)
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
