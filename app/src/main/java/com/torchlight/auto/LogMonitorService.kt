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
    private var monitorThread: Thread? = null
    @Volatile private var running = false

    companion object {
        // 火炬之光可能的日志路径（不同版本可能不同）
        private val LOG_PATHS = listOf(
            "/storage/emulated/0/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log",
            "/storage/emulated/0/Android/data/com.xindong.torchlight/files/UE4Game/TorchlightMobile/TorchlightMobile/Saved/Logs/TorchlightMobile.log",
            "/storage/emulated/0/Android/data/com.xd.torchlight/files/UE4Game/TorchlightMobile/TorchlightMobile/Saved/Logs/TorchlightMobile.log"
        )
        private val DROP_PATTERNS = listOf(
            Regex("掉落\\s+(\\S+)\\s+x\\s*(\\d+)"),
            Regex("Pickup\\s+(\\S+)\\s+x\\s*(\\d+)"),
            Regex("AddItem.*?(\\S+).*?Count[=:]\\s*(\\d+)"),
            Regex("获得.*?(\\S+).*?x\\s*(\\d+)")
        )
        private const val CHANNEL_ID = "log_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_LOG_ENTRY = "com.torchlight.auto.LOG_ENTRY"
        const val ACTION_DEBUG = "com.torchlight.auto.DEBUG"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification("正在初始化..."))
            sendDebug("✅ 前台服务已启动")
        } catch (e: Exception) {
            sendDebug("💥 前台服务启动失败: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendDebug("📡 onStartCommand")
        if (!running) {
            running = true
            Thread {
                Thread.sleep(300)
                startFileMonitor()
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

    private fun startFileMonitor() {
        monitorThread = Thread {
            try {
                sendDebug("🔍 检查 Shizuku...")
                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val pingMethod = shizukuClass.getMethod("pingBinder")
                val connected = pingMethod.invoke(null) as? Boolean ?: false
                if (!connected) {
                    sendDebug("❌ Shizuku 未连接")
                    running = false
                    return@Thread
                }

                val checkMethod = shizukuClass.getMethod("checkSelfPermission")
                val granted = checkMethod.invoke(null) as? Int ?: -1
                if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendDebug("❌ Shizuku 未授权本应用")
                    running = false
                    return@Thread
                }
                sendDebug("✅ Shizuku 已授权")

                // 找到可用的日志文件
                var validPath: String? = null
                for (path in LOG_PATHS) {
                    sendDebug("🔍 检查路径: $path")
                    if (fileExists(shizukuClass, path)) {
                        validPath = path
                        sendDebug("✅ 找到日志文件: $path")
                        break
                    }
                }

                if (validPath == null) {
                    sendDebug("❌ 找不到游戏日志文件")
                    sendDebug("👉 可能路径变了，或游戏未生成日志")
                    sendDebug("👉 尝试用 logcat 方案...")
                    startLogcatFallback(shizukuClass)
                    return@Thread
                }

                sendDebug("🚀 开始监听日志文件...")
                readLogFile(shizukuClass, validPath)

            } catch (e: Exception) {
                sendDebug("💥 异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("LogMonitor", "Crash", e)
            } finally {
                running = false
            }
        }
        monitorThread?.start()
    }

    private fun fileExists(shizukuClass: Class<*>, path: String): Boolean {
        return try {
            val method = shizukuClass.declaredMethods.find {
                it.name == "newProcess" && it.parameterCount == 3
            }
            method?.isAccessible = true
            val process = method?.invoke(
                null,
                arrayOf("sh", "-c", "test -f \"$path\" && echo YES || echo NO"),
                null, null
            ) as? Process ?: return false

            val result = BufferedReader(InputStreamReader(process.inputStream)).readLine()?.trim()
            process.waitFor()
            result == "YES"
        } catch (e: Exception) {
            false
        }
    }

    private fun readLogFile(shizukuClass: Class<*>, path: String) {
        try {
            val method = shizukuClass.declaredMethods.find {
                it.name == "newProcess" && it.parameterCount == 3
            }
            method?.isAccessible = true

            // 先获取文件当前行数，避免输出历史内容太多
            sendDebug("📖 正在打开日志...")

            val process = method?.invoke(
                null,
                arrayOf("sh", "-c", "tail -f -n 0 \"$path\""),
                null, null
            ) as? Process

            if (process == null) {
                sendDebug("❌ 无法读取日志文件")
                return
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var count = 0

            sendDebug("📥 正在实时监控，去游戏里捡东西...")

            while (running) {
                line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    sendDebug("读取中断: ${e.message}")
                    break
                }

                if (line == null) {
                    Thread.sleep(500)
                    continue
                }

                count++
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // 显示所有包含关键词的行
                if (containsKeyword(trimmed)) {
                    sendDebug("[$count] $trimmed")
                    processLine(trimmed)
                }
            }

            try { reader.close() } catch (_: Exception) {}
            sendDebug("🏁 监控结束，共 $count 行")

        } catch (e: Exception) {
            sendDebug("💥 读文件异常: ${e.message}")
        }
    }

    private fun startLogcatFallback(shizukuClass: Class<*>) {
        try {
            val method = shizukuClass.declaredMethods.find {
                it.name == "newProcess" && it.parameterCount == 3
            }
            method?.isAccessible = true

            val process = method?.invoke(
                null,
                arrayOf("logcat", "-v", "threadtime"),
                null, null
            ) as? Process

            if (process == null) {
                sendDebug("❌ logcat 也失败了")
                return
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var count = 0

            sendDebug("📥 logcat 备用方案启动...")

            while (running) {
                line = reader.readLine()
                if (line == null) break
                count++
                if (containsKeyword(line)) {
                    sendDebug("[logcat-$count] $line")
                    processLine(line)
                }
            }
        } catch (e: Exception) {
            sendDebug("💥 logcat 备用也失败: ${e.message}")
        }
    }

    private fun containsKeyword(line: String): Boolean {
        val keywords = listOf("pickup", "drop", "item", "获得", "掉落", "拾取",
            "additem", "itemid", "奖励", "战利品", "物品", "通货", "装备",
            "传奇", "稀有", "史诗", "legendary", "rare", "currency")
        return keywords.any { line.contains(it, ignoreCase = true) }
    }

    private fun processLine(line: String) {
        for (pattern in DROP_PATTERNS) {
            val match = pattern.find(line)
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
                return
            }
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
            monitorThread?.interrupt()
            monitorThread?.join(500)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
