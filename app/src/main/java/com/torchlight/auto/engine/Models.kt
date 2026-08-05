package com.torchlight.auto.engine

data class GameItem(
    val name: String,
    val quantity: Int = 1,
    val quality: String = "普通",
    val type: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class LogLevel { INFO, ITEM, GOLD, ERROR, WARNING }

data class LogParseResult(
    val items: List<GameItem>,
    val goldAmount: Long = 0,
    val isInventoryFull: Boolean = false,
    val isDisconnected: Boolean = false,
    val rawText: String = ""
)