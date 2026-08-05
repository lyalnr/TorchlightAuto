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
import java.io.InputStreamReader

class LogMonitorService : Service() {
    private var logcatThread: Thread? = null
    @Volatile private var running = false

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

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification("正在初始化..."))
            sendDebug("✅ 前台服务已启动")
        } catch (e: Exception) {
            sendDebug("💥 前台服务启动失败: ${e.message}")
            Log.e("LogMonitor", "Foreground service failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendDebug("📡 onStartCommand")
        if (!running) {
            running = true
            Thread {
                Thread.sleep(200)
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
                Log.e("LogMonitor", "Channel failed", e)
            }
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("日志监控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                sendDebug("🔍 检查 Shizuku...")
                
                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val pingMethod = shizukuClass.getMethod("pingBinder")
                val connected = pingMethod.invoke(null) as? Boolean ?: false
                
                if (!connected) {
                    sendDebug("❌ Shizuku 未连接")
                    sendDebug("👉 去 Shizuku → 应用管理 → 找到「日志监控」→ 允许")
                    running = false
                    return@Thread
                }

                sendDebug("✅ Shizuku 已连接")
                
                // 检查 Shizuku 是否授权了本应用
                val uidMethod = shizukuClass.getMethod("getUid")
                val uid = uidMethod.invoke(null) as? Int ?: -1
                if (uid <= 0) {
                    sendDebug("❌ Shizuku 未授权本应用")
                    sendDebug("👉 去 Shizuku → 应用管理 → 日志监控 → 打开开关")
                    running = false
                    return@Thread
                }
                sendDebug("✅ Shizuku 已授权 (uid=$uid)")

                sendDebug("🚀 启动 logcat...")
                
                val method = shizukuClass.declaredMethods.find { 
                    it.name == "newProcess" && it.parameterCount == 3 
                }
                if (method == null) {
                    sendDebug("❌ 找不到 newProcess")
                    running = false
                    return@Thread
                }
                method.isAccessible = true
                
                val process = method.invoke(
                    null,
                    arrayOf("logcat", "-v", "threadtime"),
                    null,
                    null
                ) as? Process

                if (process == null) {
                    sendDebug("❌ 无法创建进程（Shizuku 未授权？）")
                    running = false
                    return@Thread
                }

                sendDebug("📥 logcat 启动成功，去游戏里捡东西...")
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
                sendDebug("🏁 结束，共 $count 行")

            } catch (e: Exception) {
                sendDebug("💥 异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("LogMonitor", "Crash", e)
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
        try {
            logcatThread?.interrupt()
            logcatThread?.join(500)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
