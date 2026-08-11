package com.torchlight.auto

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import com.torchlight.auto.data.DropRepository
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener

class LogMonitor(private val context: Context) {
    companion object {
        const val ACTION_LOG_DROP = "com.torchlight.auto.LOG_DROP"
        const val ACTION_LOG_DEBUG = "com.torchlight.auto.LOG_DEBUG"
        const val ACTION_MAP_STATE = "com.torchlight.auto.MAP_STATE"
        const val LOG_PATH = "/sdcard/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
        const val SHIZUKU_REQ_CODE = 1001
    }

    private var running = false
    private var lastSize = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 500L
    private val idTable = mutableMapOf<String, String>()
    private val priceTable = mutableMapOf<String, Float>()

    private val permissionListener = OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQ_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                sendDebug("✅ Shizuku 授权成功")
                doStart()
            } else {
                sendDebug("❌ Shizuku 授权被拒绝")
            }
        }
    }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        loadTables()
    }

    fun destroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        stop()
    }

    fun start() {
        if (running) return

        if (!Shizuku.pingBinder()) {
            sendDebug("❌ Shizuku 未运行，请先启动 Shizuku")
            return
        }

        if (Shizuku.isPreV11()) {
            sendDebug("❌ Shizuku 版本过低")
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            doStart()
        } else {
            sendDebug("🔄 请求 Shizuku 权限...")
            Shizuku.requestPermission(SHIZUKU_REQ_CODE)
        }
    }

    private fun doStart() {
        // 检查日志文件是否存在
        val check = execShizuku("test -f \"$LOG_PATH\" && echo yes || echo no")?.trim()
        if (check != "yes") {
            sendDebug("❌ 日志文件不存在，请确认游戏已运行")
            return
        }

        running = true
        val initialSize = execShizuku("wc -c < \"$LOG_PATH\"")?.trim()?.toLongOrNull() ?: 0
        lastSize = initialSize
        sendDebug("🎮 日志监听已启动 | 初始大小: $lastSize bytes")
        sendDebug("📁 $LOG_PATH")
        handler.post(checkRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        sendDebug("🔴 日志监听已停止")
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val newSize = execShizuku("wc -c < \"$LOG_PATH\"")?.trim()?.toLongOrNull() ?: lastSize
            if (newSize > lastSize) {
                val diff = newSize - lastSize
                val newContent = execShizuku("dd if=\"$LOG_PATH\" bs=1 skip=$lastSize count=$diff 2>/dev/null")
                newContent?.let { processLog(it) }
                lastSize = newSize
            }
            handler.postDelayed(this, checkInterval)
        }
    }

    private fun processLog(text: String) {
        val mapState = detectMapState(text)
        if (mapState != null) {
            if (mapState) {
                DropRepository.startNewMap()
                sendMapState(true)
                sendDebug("🗺️ 进入地图 | 第 ${DropRepository.mapCount} 局")
            } else {
                DropRepository.endCurrentMap()
                sendMapState(false)
                sendDebug("🏠 离开地图")
            }
        }
        val blocks = LogParser.scanDropBlocks(text)
        for (block in blocks) {
            val data = LogParser.convertFromLogStructure(block)
            processDropData(data)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processDropData(data: Map<String, Any>) = processDropRecursive(data)

    @Suppress("UNCHECKED_CAST")
    private fun processDropRecursive(data: Map<String, Any>, path: String = "") {
        for ((key, value) in data) {
            if (value is Map<*, *>) {
                val mapValue = value as Map<String, Any>
                if ("item" in mapValue) {
                    val hasPicked = "Picked" in mapValue ||
                        (mapValue["item"] is Map<*, *> && "Picked" in (mapValue["item"] as Map<*, *>))
                    if (hasPicked) processDropItem(mapValue)
                }
                processDropRecursive(mapValue, if (path.isEmpty()) key else "$path.$key")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processDropItem(itemData: Map<String, Any>) {
        var picked = false
        var itemInfo = itemData["item"] as? Map<String, Any>
        if ("Picked" in itemData) picked = itemData["Picked"] as? Boolean ?: false
        else if (itemInfo != null && "Picked" in itemInfo) picked = itemInfo["Picked"] as? Boolean ?: false
        if (!picked) return

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
        DropRepository.addDrop(itemName, unitPrice, "日志")
        sendDrop(itemName, unitPrice, num)
        sendDebug("💎 掉落: $itemName x$num = ${totalPrice.toInt()}火")
    }

    private fun detectMapState(text: String): Boolean? {
        if (text.contains("PageApplyBase@ _UpdateGameEnd: LastSceneName =") &&
            text.contains("NextSceneName = World'/Game/Art/Maps/")) return true
        if (text.contains("NextSceneName = World'/Game/Art/Maps/01SD/XZ_YuJinZhiXiBiNanSuo200")) return false
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

    private fun execShizuku(cmd: String): String? {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val result = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            result
        } catch (e: Exception) {
            sendDebug("❌ Shizuku 执行失败: ${e.message}")
            null
        }
    }

    private fun sendDrop(name: String, price: Float, quantity: Int) {
        context.sendBroadcast(Intent(ACTION_LOG_DROP).apply {
            putExtra("name", name); putExtra("price", price); putExtra("quantity", quantity)
        })
    }
    private fun sendDebug(msg: String) {
        context.sendBroadcast(Intent(ACTION_LOG_DEBUG).apply { putExtra("msg", msg) })
    }
    private fun sendMapState(inMap: Boolean) {
        context.sendBroadcast(Intent(ACTION_MAP_STATE).apply { putExtra("inMap", inMap) })
    }
}
