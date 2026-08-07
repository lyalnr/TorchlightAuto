package com.torchlight.auto.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ItemEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "torchlight_db")
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()  // 小工具数据库，允许主线程查询
                    .build().also { INSTANCE = it }
            }
        }
    }
}
