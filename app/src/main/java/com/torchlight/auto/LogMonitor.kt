package com.torchlight.auto

import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import com.torchlight.auto.data.DropRepository
import java.io.File
import java.io.RandomAccessFile

/**
 * 火炬之光无限日志监听器（FurTorch 核心逻辑 Android 移植）
 *
 * ⚠️ 权限说明：Android 11+ 默认无法访问 Android/data 目录。
 * 你需要以下任一方式：
 * 1. 申请 MANAGE_EXTERNAL_STORAGE 权限（所有文件访问权限）
 * 2. 使用 Shizuku 提升权限（本项目已引入 Shizuku 依赖）
 * 3. 通过 SAF 让用户手动选择日志目录
 */
class LogMonitor(private val context: Context) {
    companion object {
        const val ACTION_LOG_DROP = "com.torchlight.auto.LOG_DROP"
        const val ACTION_LOG_DEBUG = "com.torchlight.auto.LOG_DEBUG"
        const val ACTION_MAP_STATE = "com.torchlight.auto.MAP_STATE"

        // 火炬之光无限 Android 版日志路径
        const val LOG_PATH = "/sdcard/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
    }

    private var fileObserver: FileObserver? = null
    private var running = false
    private var lastPosition = 0L
    private val handler = Handler(Looper.getMainLooper())

    // 物品ID表（从 FurTorch 的 id_table.conf / full_table.json 简化提取）
    private val idTable = mutableMapOf<String, String>()
    private val priceTable = mutableMapOf<String, Float>()

    fun start() {
        if (running) return
        val logFile = File(LOG_PATH)
        if (!logFile.exists()) {
            sendDebug("❌ 日志文件不存在，请确认游戏已运行并授权访问: $LOG_PATH")
            return
        }

        running = true
        lastPosition = logFile.length()
        loadTables()

        fileObserver = object : FileObserver(logFile.absolutePath, MODIFY or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (event == MODIFY || event == CLOSE_WRITE) {
                    handler.post { readNewLines() }
                }
            }
        }
        fileObserver?.startWatching()

        sendDebug("🎮 日志监听已启动")
        sendDebug("📁 路径: $LOG_PATH")
    }

    fun stop() {
        running = false
        fileObserver?.stopWatching()
        fileObserver = null
        sendDebug("🔴 日志监听已停止")
    }

    private fun readNewLines() {
        try {
            val file = File(LOG_PATH)
            if (!file.exists()) return

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(lastPosition)
                val sb = StringBuilder()
                var line: String?
                while (raf.readLine()?.also { line = it } != null) {
                    val bytes = line!!.toByteArray(Charsets.ISO_8859_1)
                    sb.appendLine(String(bytes, Charsets.UTF_8))
                }
                lastPosition = raf.filePointer

                val newText = sb.toString()
                if (newText.isNotBlank()) {
                    processLog(newText)
                }
            }
        } catch (e: Exception) {
            sendDebug("❌ 读取日志失败: ${e.message}")
        }
    }

    private fun processLog(text: String) {
        // 1. 检测地图进出（FurTorch deal_change 逻辑移植）
        val mapState = detectMapState(text)
        if (mapState != null) {
            if (mapState) {
                DropRepository.startNewMap()
                sendMapState(true)
                sendDebug("🗺️ 进入地图 | 当前第 ${DropRepository.mapCount} 局")
            } else {
                DropRepository.endCurrentMap()
                sendMapState(false)
                sendDebug("🏠 离开地图 | 本局收益统计完成")
            }
        }

        // 2. 扫描掉落块并解析
        val blocks = LogParser.scanDropBlocks(text)
        for (block in blocks) {
            val data = LogParser.convertFromLogStructure(block)
            processDropData(data)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processDropData(data: Map<String, Any>) {
        processDropRecursive(data)
    }

    @Suppress("UNCHECKED_CAST")
    private fun processDropRecursive(data: Map<String, Any>, path: String = "") {
        for ((key, value) in data) {
            if (value is Map<*, *>) {
                val mapValue = value as Map<String, Any>
                if ("item" in mapValue) {
                    val hasPicked = "Picked" in mapValue ||
                        (mapValue["item"] is Map<*, *> && "Picked" in (mapValue["item"] as Map<*, *>))
                    if (hasPicked) {
                        processDropItem(mapValue)
                    }
                }
                processDropRecursive(mapValue, if (path.isEmpty()) key else "$path.$key")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processDropItem(itemData: Map<String, Any>) {
        var picked = false
        var itemInfo = itemData["item"] as? Map<String, Any>

        if ("Picked" in itemData) {
            picked = itemData["Picked"] as? Boolean ?: false
        } else if (itemInfo != null && "Picked" in itemInfo) {
            picked = itemInfo["Picked"] as? Boolean ?: false
        }
        if (!picked) return

        // 处理 SpecialInfo 嵌套（FurTorch 逻辑）
        if (itemInfo is Map<*, *>) {
            val info = itemInfo as Map<String, Any>
            val specialInfo = info["SpecialInfo"] as? Map<String, Any>
            if (specialInfo != null) {
                val merged = info.toMutableMap()
                if ("BaseId" in specialInfo) merged["BaseId"] = specialInfo["BaseId"]!!
                if ("Num" in specialInfo) merged["Num"] = specialInfo["Num"]!!
                itemInfo = merged
            }
        }

        val info = itemInfo as? Map<String, Any> ?: return
        val baseId = info["BaseId"]?.toString() ?: return
        val num = (info["Num"] as? Number)?.toInt() ?: 1

        val itemName = idTable[baseId] ?: "未知物品($baseId)"
        if (itemName.isBlank()) return

        val unitPrice = priceTable[baseId] ?: 0f
        val totalPrice = unitPrice * num

        // 计入仓库
        DropRepository.addDrop(itemName, unitPrice, "日志")

        sendDrop(itemName, unitPrice, num)
        sendDebug("💎 掉落: $itemName x$num = ${totalPrice.toInt()}火")
    }

    private fun detectMapState(text: String): Boolean? {
        if (text.contains("PageApplyBase@ _UpdateGameEnd: LastSceneName =") &&
            text.contains("NextSceneName = World'/Game/Art/Maps/")) {
            return true
        }
        if (text.contains("NextSceneName = World'/Game/Art/Maps/01SD/XZ_YuJinZhiXiBiNanSuo200")) {
            return false
        }
        return null
    }

    private fun loadTables() {
        idTable["100200"] = "初火灵砂"
        idTable["100300"] = "初火源质"
        idTable["100301"] = "初火微尘"
        idTable["110000"] = "灰烬"
        idTable["120000"] = "记忆碎片"

        priceTable["100200"] = 999f
        priceTable["100300"] = 1f
        priceTable["100301"] = 0.1f
        priceTable["110000"] = 0.1f
        priceTable["120000"] = 0.5f
    }

    private fun sendDrop(name: String, price: Float, quantity: Int) {
        context.sendBroadcast(Intent(ACTION_LOG_DROP).apply {
            putExtra("name", name)
            putExtra("price", price)
            putExtra("quantity", quantity)
        })
    }

    private fun sendDebug(msg: String) {
        context.sendBroadcast(Intent(ACTION_LOG_DEBUG).apply {
            putExtra("msg", msg)
        })
    }

    private fun sendMapState(inMap: Boolean) {
        context.sendBroadcast(Intent(ACTION_MAP_STATE).apply {
            putExtra("inMap", inMap)
        })
    }
}
