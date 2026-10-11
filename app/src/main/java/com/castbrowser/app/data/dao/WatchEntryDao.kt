package com.castbrowser.app.data.dao

import androidx.room.*
import com.castbrowser.app.data.entity.SeriesEntity
import com.castbrowser.app.data.entity.WatchEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchEntryDao {
    @Query("SELECT * FROM watch_entry ORDER BY lastWatchedAt DESC")
    fun getAllWatchEntries(): Flow<List<WatchEntryEntity>>

    @Query("SELECT * FROM watch_entry WHERE finished = 0 ORDER BY lastWatchedAt DESC")
    fun getContinueWatchingEntries(): Flow<List<WatchEntryEntity>>

    @Query("SELECT * FROM watch_entry WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: Long): WatchEntryEntity?

    @Query("SELECT * FROM watch_entry WHERE pageUrl = :pageUrl LIMIT 1")
    suspend fun getEntryByPageUrl(pageUrl: String): WatchEntryEntity?

    @Query("SELECT * FROM watch_entry WHERE seriesKey = :seriesKey ORDER BY season DESC, episode DESC LIMIT 1")
    suspend fun getLatestEntryForSeries(seriesKey: String): WatchEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entry: WatchEntryEntity): Long

    @Query("UPDATE watch_entry SET positionMs = :positionMs, durationMs = :durationMs, finished = :finished, lastWatchedAt = :lastWatchedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, positionMs: Long, durationMs: Long, finished: Boolean, lastWatchedAt: Long)

    @Delete
    suspend fun deleteEntry(entry: WatchEntryEntity)

    @Query("DELETE FROM watch_entry WHERE id = :id")
    suspend fun deleteEntryById(id: Long)

    // Series management
    @Query("SELECT * FROM series ORDER BY updatedAt DESC")
    fun getAllSeries(): Flow<List<SeriesEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSeries(series: SeriesEntity)
}
