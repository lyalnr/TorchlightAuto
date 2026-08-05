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
            // 先发送一条调试消息：路径和文件是否存在
            sendDebug("路径: $logPath")
            sendDebug("文件存在: ${File(logPath).exists()}")
            sendDebug("可读: ${File(logPath).canRead()}")
            startMonitoring()
        } else {
            sendDebug("错误: 未收到日志路径")
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
            .setContentText("正在监控: $logPath")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
    }

    private fun startMonitoring() {
        val dir = File(logPath).parentFile
        if (dir == null) {
            sendDebug("错误: 无法获取父目录")
            return
        }
        sendDebug("监控目录: ${dir.absolutePath}")
        
        fileObserver = object : FileObserver(dir.absolutePath, FileObserver.MODIFY or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith("UE_game.log")) {
                    sendDebug("文件变化: $path")
                    readNewLines()
                }
            }
        }
        fileObserver.startWatching()
        sendDebug("FileObserver 已启动")
        
        // 立即读取一次
        readNewLines()
    }

    private fun readNewLines() {
        try {
            if (!Shizuku.pingBinder()) {
                sendDebug("错误: Shizuku 未连接")
                return
            }

            val totalLines = getLineCount()
            if (totalLines == null) {
                sendDebug("错误: 无法获取行数")
                return
            }
            sendDebug("总行数: $totalLines, 上次: $lastLineCount")

            if (totalLines > lastLineCount) {
                val startLine = lastLineCount + 1
                sendDebug("读取行 $startLine 到 $totalLines")
                readLinesFrom(startLine)
                lastLineCount = totalLines
            } else {
                sendDebug("无新内容")
            }
        } catch (e: Exception) {
            sendDebug("异常: ${e.javaClass.simpleName}: ${e.message}")
            Log.e("LogMonitor", "读取日志失败", e)
        }
    }

    private fun getLineCount(): Long? {
        val process = execShell("wc -l < \"$logPath\" 2>&1") ?: return null
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText().trim()
        reader.close()
        process.waitFor()
        
        // wc 如果文件不存在会输出错误信息
        if (output.isEmpty()) return 0
        return output.toLongOrNull()
    }

    private fun readLinesFrom(startLine: Long) {
        val process = execShell("tail -n +$startLine \"$logPath\" 2>&1") ?: return
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        var count = 0
        while (reader.readLine().also { line = it } != null) {
            count++
            val trimmed = line!!.trim()
            if (trimmed.isNotEmpty()) {
                // 每读一行都发出去，让用户看到原始内容
                sendDebug("[$count] $trimmed")
                processLine(trimmed)
            }
        }
        reader.close()
        process.waitFor()
        sendDebug("本次读取 $count 行")
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
        // 尝试多种可能的掉落格式
        val patterns = listOf(
            Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)"),
            Regex("获得\\s+(\\S+)\\s+x\\s*(\\d+)"),
            Regex("pickup\\s+(\\S+)\\s+x\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("drop\\s+(\\S+)\\s+x\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\S+)\\s+x\\s*(\\d+)\\s*掉落"),
            Regex("Item\\[(\\d+)\\].*Num\\[(\\d+)\\]"),
            Regex("AddItem.*id=(\\d+).*count=(\\d+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(line)
            if (match != null) {
                val itemName = match.groupValues[1]
                val quantity = match.groupValues[2].toIntOrNull() ?: 1
                sendEntry(itemName, quantity, line)
                return
            }
        }
        
        // 如果没匹配到具体格式，但行里有"掉落/获得"关键词，也显示出来
        if (line.contains("掉落") || line.contains("获得") || line.contains("pickup") || line.contains("drop")) {
            sendDebug("【可能相关】$line")
        }
    }

    private fun sendEntry(item: String, quantity: Int, rawLine: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            item = item,
            quantity = quantity,
            fireValue = 0,
            rawLine = rawLine
        )
        val intent = Intent("LOG_ENTRY")
        intent.putExtra("entry", entry)
        sendBroadcast(intent)
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
        if (::fileObserver.isInitialized) {
            fileObserver.stopWatching()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
