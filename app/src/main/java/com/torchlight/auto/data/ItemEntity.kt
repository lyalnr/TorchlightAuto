package com.torchlight.auto.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "price_table")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val price: Float = -1f,
    val color: String = "未知",
    val enabled: Boolean = true
)
