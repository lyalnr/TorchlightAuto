package com.torchlight.auto.data
data class TodayDrop(val name: String, var quantity: Int, var unitPrice: Float, val color: String)
object DropRepository {
    val todayDrops = mutableListOf<TodayDrop>()
    var totalFire: Float = 0f
        private set
    val listeners = mutableListOf<() -> Unit>()
    fun addDrop(name: String, unitPrice: Float, color: String) {
        val exist = todayDrops.find { it.name == name }
        if (exist != null) exist.quantity++ else todayDrops.add(TodayDrop(name, 1, unitPrice, color))
        recalculate()
        notifyListeners()
    }
    fun updatePrice(name: String, newPrice: Float) {
        todayDrops.find { it.name == name }?.unitPrice = newPrice
        recalculate()
        notifyListeners()
    }
    fun recalculate() {
        totalFire = todayDrops.filter { it.unitPrice >= 0 }
            .sumOf { it.quantity * it.unitPrice.toDouble() }.toFloat()
    }
    fun clear() {
        todayDrops.clear()
        totalFire = 0f
        notifyListeners()
    }
    private fun notifyListeners() = listeners.forEach { it() }
}
