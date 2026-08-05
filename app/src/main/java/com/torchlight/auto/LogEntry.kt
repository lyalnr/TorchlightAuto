package com.torchlight.auto

import java.io.Serializable

data class LogEntry(
    val timestamp: Long,
    val item: String,
    val quantity: Int,
    val fireValue: Int,
    val rawLine: String
) : Serializable
