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
        sendDebug("📡 onStartCommand 被调用")
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
                Log.e("LogMonitor", "Channel create failed", e)
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

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            Log.e("LogMonitor", "Notify failed", e)
        }
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                sendDebug("🔍 正在检查 Shizuku 连接...")
                
                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val pingMethod = shizukuClass.getMethod("pingBinder")
                val connected = pingMethod.invoke(null) as? Boolean ?: false
                
                if (!connected) {
                    sendDebug("❌ Shizuku 未连接！")
                    sendDebug("👉 请打开 Shizuku 应用 → 启动服务 → 找到「火炬助手」→ 允许")
                    updateNotification("Shizuku 未连接")
                    running = false
                    return@Thread
                }

                sendDebug("✅ Shizuku 已连接")
                sendDebug("🚀 正在启动 logcat，请去游戏里捡东西...")
                updateNotification("正在抓取日志...")
                
                val method = shizukuClass.declaredMethods.find { 
                    it.name == "newProcess" && it.parameterCount == 3 
                }
                if (method == null) {
                    sendDebug("❌ 找不到 Shizuku.newProcess 方法")
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
                    sendDebug("❌ 无法创建 logcat 进程")
                    updateNotification("进程创建失败")
                    running = false
                    return@Thread
                }

                sendDebug("📥 logcat 已启动，等待游戏日志...")
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
                updateNotification("监控已停止")

            } catch (e: Exception) {
                sendDebug("💥 严重异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("LogMonitor", "Service crash", e)
                updateNotification("错误: ${e.message}")
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
        sendDebug("🛑 Service 已销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
