package com.torchlight.auto.data
import androidx.room.*
@Dao
interface ItemDao {
    @Query("SELECT * FROM price_table ORDER BY id DESC")
    fun getAll(): List<ItemEntity>
    @Query("SELECT * FROM price_table WHERE name = :name LIMIT 1")
    fun getByName(name: String): ItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: ItemEntity)
    @Delete
    fun delete(item: ItemEntity)
    @Update
    fun update(item: ItemEntity)
}
