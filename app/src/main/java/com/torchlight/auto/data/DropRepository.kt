package com.torchlight.auto.data

data class TodayDrop(
    val name: String,
    var quantity: Int,
    var unitPrice: Float,
    val color: String
)

// 借鉴 FurTorch：单局地图统计
data class MapSession(
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    val drops: MutableList<TodayDrop> = mutableListOf(),
    var cost: Float = 0f
) {
    val durationMs: Long
        get() = (endTime ?: System.currentTimeMillis()) - startTime

    val durationMin: Float
        get() = durationMs / 60000f

    val income: Float
        get() = drops.filter { it.unitPrice >= 0 }
            .sumOf { it.quantity * it.unitPrice.toDouble() }.toFloat() - cost

    val firePerMin: Float
        get() = if (durationMin > 0) income / durationMin else 0f
}

object DropRepository {
    val todayDrops = mutableListOf<TodayDrop>()
    var totalFire: Float = 0f
        private set

    // === 新增：地图级统计（来自 FurTorch 思路） ===
    val mapSessions = mutableListOf<MapSession>()
    var currentMap: MapSession? = null
        private set
    var totalTimeMs: Long = 0
        private set
    var mapCount: Int = 0
        private set

    val listeners = mutableListOf<() -> Unit>()

    fun startNewMap(cost: Float = 0f) {
        currentMap?.let { endCurrentMap() }
        currentMap = MapSession(cost = cost)
        mapCount++
    }

    fun endCurrentMap() {
        currentMap?.let {
            it.endTime = System.currentTimeMillis()
            totalTimeMs += it.durationMs
            mapSessions.add(it)
            currentMap = null
        }
    }

    fun addDrop(name: String, unitPrice: Float, color: String) {
        // 今日总计
        val exist = todayDrops.find { it.name == name }
        if (exist != null) exist.quantity++ else todayDrops.add(TodayDrop(name, 1, unitPrice, color))

        // 当前地图
        currentMap?.let { map ->
            val mapExist = map.drops.find { it.name == name }
            if (mapExist != null) mapExist.quantity++ else map.drops.add(TodayDrop(name, 1, unitPrice, color))
        }

        recalculate()
        notifyListeners()
    }

    fun updatePrice(name: String, newPrice: Float) {
        todayDrops.find { it.name == name }?.unitPrice = newPrice
        currentMap?.drops?.find { it.name == name }?.unitPrice = newPrice
        recalculate()
        notifyListeners()
    }

    fun recalculate() {
        totalFire = todayDrops.filter { it.unitPrice >= 0 }
            .sumOf { it.quantity * it.unitPrice.toDouble() }.toFloat()
    }

    fun clear() {
        todayDrops.clear()
        mapSessions.clear()
        currentMap = null
        totalFire = 0f
        totalTimeMs = 0
        mapCount = 0
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it() }
}
