package com.torchlight.auto.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "price_table")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val price: Float = -1f,
    val enabled: Boolean = true,
    val enabledColors: String = "红色,金色,紫色,蓝色"
)
