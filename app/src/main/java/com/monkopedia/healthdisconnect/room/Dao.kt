package com.monkopedia.healthdisconnect.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DataViewDao {
    @Query("SELECT * FROM data_views WHERE id = :id")
    fun dataView(id: Int): Flow<DataViewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(view: DataViewEntity)

    @Update
    suspend fun update(view: DataViewEntity)
}

@Dao
interface DataViewInfoDao {
    @Query("SELECT * FROM data_view_info ORDER BY ordering ASC")
    fun allOrdered(): Flow<List<DataViewInfoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(info: DataViewInfoEntity)

    @Query("SELECT MAX(ordering) FROM data_view_info")
    suspend fun maxOrdering(): Int?

    @Query("SELECT COUNT(*) FROM data_view_info")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM data_views")
    suspend fun viewCount(): Int
}
