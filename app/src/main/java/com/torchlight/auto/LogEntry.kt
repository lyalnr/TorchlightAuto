package com.torchlight.auto

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LogEntry(
    val timestamp: Long,
    val item: String,
    val quantity: Int,
    val fireValue: Int,
    val rawLine: String
) : Parcelable
