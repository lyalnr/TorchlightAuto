package com.torchlight.auto.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.torchlight.auto.R
import com.torchlight.auto.log.LogMonitor
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvStats: TextView
    private lateinit var svLog: ScrollView
    private var logMonitor: LogMonitor? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logPath = "/storage/emulated/0/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tv_log)
        tvStats = findViewById(R.id.tv_stats)
        svLog = findViewById(R.id.sv_log)

        startLogMonitor()
    }

    private fun startLogMonitor() {
        logMonitor = LogMonitor(logPath)
        scope.launch {
            logMonitor?.logEntries?.collect { result ->
                if (result.items.isNotEmpty()) {
                    result.items.forEach { item ->
                        val emoji = when (item.quality) {
                            "传奇" -> "🟡"; "稀有" -> "🟠"; "魔法" -> "🔵"; else -> "⚪"
                        }
                        appendLog("$emoji 获得: ${item.name} x${item.quantity} [${item.quality}]")
                    }
                }
                if (result.goldAmount > 0) appendLog("💰 金币 +${result.goldAmount}")
                if (result.isInventoryFull) appendLog("⚠️ 背包已满!")
                if (result.isDisconnected) appendLog("⚠️ 断线!")
                updateStats()
            }
        }
        logMonitor?.start()
        appendLog("📊 日志监听已启动")
    }

    private fun updateStats() {
        val stats = logMonitor?.getStats() ?: emptyMap()
        val total = stats["totalItems"] as? Int ?: 0
        val legendary = stats["legendary"] as? Int ?: 0
        val rare = stats["rare"] as? Int ?: 0
        val gold = stats["totalGold"] as? Long ?: 0L
        runOnUiThread {
            tvStats.text = "物品: $total | 传奇: $legendary | 稀有: $rare | 金币: $gold"
        }
    }

    private fun appendLog(message: String) {
        val time = dateFormat.format(Date())
        runOnUiThread {
            logBuffer.append("[$time] $message\n")
            if (logBuffer.lines().size > 200) {
                val lines = logBuffer.lines().takeLast(200)
                logBuffer.clear(); logBuffer.append(lines.joinToString("\n"))
            }
            tvLog.text = logBuffer.toString()
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logMonitor?.stop()
        scope.cancel()
    }
}