package com.castbrowser.app.data.dao

import androidx.room.*
import com.castbrowser.app.data.entity.CastDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CastDeviceDao {
    @Query("SELECT * FROM cast_device ORDER BY lastUsedAt DESC")
    fun getAllDevices(): Flow<List<CastDeviceEntity>>

    @Query("SELECT * FROM cast_device ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun getLastUsedDevice(): CastDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(device: CastDeviceEntity)

    @Delete
    suspend fun delete(device: CastDeviceEntity)

    @Query("DELETE FROM cast_device WHERE id = :id")
    suspend fun deleteById(id: String)
}
