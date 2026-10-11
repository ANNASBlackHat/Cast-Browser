package com.castbrowser.app.data.dao

import androidx.room.*
import com.castbrowser.app.data.entity.HistoryVisitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_visit ORDER BY visitedAt DESC")
    fun getAllHistory(): Flow<List<HistoryVisitEntity>>

    @Query("SELECT * FROM history_visit WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY visitedAt DESC")
    fun searchHistory(query: String): Flow<List<HistoryVisitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisit(visit: HistoryVisitEntity): Long

    @Query("DELETE FROM history_visit WHERE id = :id")
    suspend fun deleteVisitById(id: Long)

    @Query("DELETE FROM history_visit")
    suspend fun clearAllHistory()
}
