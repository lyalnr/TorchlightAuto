package com.torchlight.auto.log

import android.os.FileObserver
import android.util.Log
import com.torchlight.auto.engine.GameItem
import com.torchlight.auto.engine.LogParseResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.regex.Pattern

class LogMonitor(private val logPath: String) {

    companion object {
        private const val TAG = "LogMonitor"
        private val ITEM_PATTERN = Pattern.compile("picked up\\s+(.+?)(?:\\s+x(\\d+))?$", Pattern.CASE_INSENSITIVE)
        private val GOLD_PATTERN = Pattern.compile("\\+?(\\d+)\\s+gold", Pattern.CASE_INSENSITIVE)
        private val LEGENDARY = Pattern.compile("legendary|unique|传奇|暗金", Pattern.CASE_INSENSITIVE)
        private val RARE = Pattern.compile("rare|稀有", Pattern.CASE_INSENSITIVE)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _logEntries = MutableSharedFlow<LogParseResult>(extraBufferCapacity = 64)
    val logEntries: SharedFlow<LogParseResult> = _logEntries
    private var observer: FileObserver? = null
    private var lastPos: Long = 0
    private val allItems = mutableListOf<GameItem>()
    private var totalGold: Long = 0

    fun start() {
        val f = File(logPath)
        if (!f.exists()) { Log.w(TAG, "日志不存在: $logPath"); return }
        lastPos = maxOf(0, f.length() - 50 * 1024)
        observer = object : FileObserver(logPath, MODIFY or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                scope.launch { readNew() }
            }
        }
        observer?.startWatching()
        scope.launch { readNew() }
    }

    private suspend fun readNew() {
        try {
            val f = File(logPath)
            if (!f.exists() || f.length() <= lastPos) { lastPos = 0; return }
            RandomAccessFile(f, "r").use { r ->
                r.seek(lastPos)
                var line = r.readLine()
                while (line != null) {
                    val s = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    parse(s)?.let { _logEntries.emit(it) }
                    line = r.readLine()
                }
                lastPos = r.filePointer
            }
        } catch (e: Exception) { Log.e(TAG, "读取失败", e) }
    }

    private fun parse(line: String): LogParseResult? {
        if (line.isBlank()) return null
        val items = mutableListOf<GameItem>()
        var gold = 0L
        ITEM_PATTERN.matcher(line).let { m ->
            if (m.find()) {
                val name = m.group(1)?.trim() ?: ""
                val qty = m.group(2)?.toIntOrNull() ?: 1
                val q = when { LEGENDARY.matcher(line).find() -> "传奇"; RARE.matcher(line).find() -> "稀有"; else -> "普通" }
                items.add(GameItem(name, qty, q))
                allItems.add(items.last())
            }
        }
        GOLD_PATTERN.matcher(line).let { m -> if (m.find()) { gold = m.group(1)?.toLongOrNull() ?: 0; totalGold += gold } }
        val invFull = line.contains("背包已满", true) || line.contains("inventory full", true)
        val disc = line.contains("disconnect", true) || line.contains("连接失败", true)
        return if (items.isNotEmpty() || gold > 0 || invFull || disc) LogParseResult(items, gold, invFull, disc, line) else null
    }

    fun getStats() = mapOf("totalItems" to allItems.size, "totalGold" to totalGold,
        "legendary" to allItems.count { it.quality == "传奇" }, "rare" to allItems.count { it.quality == "稀有" })

    fun stop() { observer?.stopWatching(); scope.cancel() }
}