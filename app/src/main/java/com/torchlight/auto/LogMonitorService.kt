package com.torchlight.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.ParcelFileDescriptor
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
        intent?.let {
            logPath = it.getStringExtra("log_path") ?: ""
        }
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
        val dir = File(logPath).parentFile ?: return
        fileObserver = object : FileObserver(dir.absolutePath, FileObserver.MODIFY or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith("UE_game.log")) {
                    readNewLines()
                }
            }
        }
        fileObserver.startWatching()
        // 首次启动读取已有内容
        readNewLines()
    }

    private fun readNewLines() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.e("LogMonitor", "Shizuku 未连接")
                return
            }

            val service = Shizuku.getService() ?: return

            // 1. 获取当前文件总行数
            val wcPfd = service.executeShellCommand("wc -l < $logPath")
            val wcReader = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(wcPfd)))
            val lineCountStr = wcReader.readText().trim()
            wcReader.close()
            val totalLines = lineCountStr.toLongOrNull() ?: 0

            if (totalLines > lastLineCount) {
                // 2. 有新增行，用 tail 读取新增部分
                val startLine = lastLineCount + 1
                val tailPfd = service.executeShellCommand("tail -n +$startLine $logPath")
                val tailReader = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(tailPfd)))
                var line: String?
                while (tailReader.readLine().also { line = it } != null) {
                    processLine(line!!)
                }
                tailReader.close()
                lastLineCount = totalLines
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
