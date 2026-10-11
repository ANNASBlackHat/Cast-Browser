package com.castbrowser.app.data.dao

import androidx.room.*
import com.castbrowser.app.data.entity.TabEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Query("SELECT * FROM tab ORDER BY position ASC")
    fun getAllTabs(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tab ORDER BY position ASC")
    suspend fun getAllTabsList(): List<TabEntity>

    @Query("SELECT * FROM tab WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTab(): TabEntity?

    @Query("SELECT * FROM tab WHERE id = :id LIMIT 1")
    suspend fun getTabById(id: String): TabEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(tab: TabEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(tabs: List<TabEntity>)

    @Query("UPDATE tab SET isActive = 0")
    suspend fun clearActiveFlag()

    @Transaction
    suspend fun setActiveTab(tabId: String) {
        clearActiveFlag()
        setTabActiveById(tabId)
    }

    @Query("UPDATE tab SET isActive = 1 WHERE id = :tabId")
    suspend fun setTabActiveById(tabId: String)

    @Delete
    suspend fun deleteTab(tab: TabEntity)

    @Query("DELETE FROM tab WHERE id = :id")
    suspend fun deleteTabById(id: String)

    @Query("DELETE FROM tab")
    suspend fun deleteAllTabs()
}
