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
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogMonitorService : Service() {
    private var logcatThread: Thread? = null
    @Volatile private var running = false
    private val logFile = File("/sdcard/Download/torchlight_service.log")

    companion object {
        private val DROP_PATTERN = Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)")
        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_LOG_ENTRY = "com.torchlight.auto.LOG_ENTRY"
        const val ACTION_DEBUG = "com.torchlight.auto.DEBUG"
        
        private val KEYWORDS = listOf(
            "pickup", "drop", "item", "获得", "掉落", "拾取",
            "additem", "itemid", "奖励", "战利品", "物品",
            "通货", "装备", "传奇", "稀有", "史诗"
        )
    }

    private fun writeFile(msg: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            FileWriter(logFile, true).use { it.appendLine("[$time] $msg") }
        } catch (e: Exception) {
            Log.e("LogMonitor", "File log failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        writeFile("=== onCreate 开始 ===")
        try {
            createNotificationChannel()
            writeFile("通知渠道创建完成")
            
            val notification = createNotification("正在初始化...")
            writeFile("通知创建完成")
            
            startForeground(NOTIFICATION_ID, notification)
            writeFile("✅ startForeground 成功")
            
            sendDebug("✅ 前台服务已启动")
        } catch (e: Exception) {
            writeFile("💥 onCreate 异常: ${e.javaClass.simpleName}: ${e.message}")
            sendDebug("💥 前台服务启动失败: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        writeFile("📡 onStartCommand 被调用")
        sendDebug("📡 onStartCommand 被调用")
        
        if (!running) {
            running = true
            Thread {
                Thread.sleep(300)
                startLogcatMonitor()
            }.start()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "日志监控服务",
                    NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            } catch (e: Exception) {
                writeFile("通知渠道创建失败: ${e.message}")
            }
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("火炬之光掉落监控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                writeFile("🔍 检查 Shizuku 连接...")
                sendDebug("🔍 检查 Shizuku 连接...")
                
                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val pingMethod = shizukuClass.getMethod("pingBinder")
                val connected = pingMethod.invoke(null) as? Boolean ?: false
                
                writeFile("Shizuku pingBinder = $connected")
                
                if (!connected) {
                    sendDebug("❌ Shizuku 未连接！")
                    sendDebug("👉 打开 Shizuku → 启动服务 → 应用管理 → 找到「火炬助手」→ 允许")
                    running = false
                    return@Thread
                }

                sendDebug("✅ Shizuku 已连接")
                sendDebug("🚀 正在启动 logcat...")
                
                val method = shizukuClass.declaredMethods.find { 
                    it.name == "newProcess" && it.parameterCount == 3 
                }
                if (method == null) {
                    sendDebug("❌ 找不到 Shizuku.newProcess 方法")
                    writeFile("找不到 newProcess 方法")
                    running = false
                    return@Thread
                }
                method.isAccessible = true
                
                writeFile("调用 newProcess...")
                val process = method.invoke(
                    null,
                    arrayOf("logcat", "-v", "threadtime"),
                    null,
                    null
                ) as? Process

                if (process == null) {
                    sendDebug("❌ 无法创建 logcat 进程（Shizuku 可能未授权本应用）")
                    sendDebug("👉 去 Shizuku → 应用管理 → 找到「火炬助手」→ 打开开关")
                    writeFile("newProcess 返回 null")
                    running = false
                    return@Thread
                }

                writeFile("logcat 进程创建成功")
                sendDebug("📥 logcat 已启动，去游戏里捡东西试试...")
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
                sendDebug("🏁 监控结束，共处理 $count 行")
                writeFile("监控结束，共 $count 行")

            } catch (e: Exception) {
                val err = "${e.javaClass.simpleName}: ${e.message}"
                sendDebug("💥 严重异常: $err")
                writeFile("严重异常: $err")
                Log.e("LogMonitor", "Service crash", e)
            } finally {
                running = false
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
        try {
            sendBroadcast(Intent(ACTION_DEBUG).putExtra("msg", msg))
        } catch (e: Exception) {
            Log.e("LogMonitor", "sendDebug error", e)
        }
    }

    override fun onDestroy() {
        running = false
        writeFile("=== onDestroy ===")
        try {
            logcatThread?.interrupt()
            logcatThread?.join(500)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
