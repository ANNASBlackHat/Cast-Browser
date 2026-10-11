package com.castbrowser.app.data

import com.castbrowser.app.data.dao.*
import com.castbrowser.app.data.entity.*
import kotlinx.coroutines.flow.Flow

interface Repository {
    // Tabs
    fun getAllTabs(): Flow<List<TabEntity>>
    suspend fun getAllTabsList(): List<TabEntity>
    suspend fun getActiveTab(): TabEntity?
    suspend fun saveTab(tab: TabEntity)
    suspend fun setActiveTab(tabId: String)
    suspend fun deleteTab(tabId: String)
    suspend fun clearTabs()

    // History
    fun getAllHistory(): Flow<List<HistoryVisitEntity>>
    fun searchHistory(query: String): Flow<List<HistoryVisitEntity>>
    suspend fun recordVisit(url: String, title: String)
    suspend fun deleteVisit(id: Long)
    suspend fun clearHistory()

    // Watch Progress
    fun getContinueWatching(): Flow<List<WatchEntryEntity>>
    suspend fun getWatchEntryByUrl(pageUrl: String): WatchEntryEntity?
    suspend fun recordOrUpdateWatch(
        pageUrl: String,
        title: String,
        host: String,
        seriesKey: String?,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long
    ): Long
    suspend fun deleteWatchEntry(id: Long)

    // Cast Devices
    fun getSavedDevices(): Flow<List<CastDeviceEntity>>
    suspend fun saveDevice(device: CastDeviceEntity)
    suspend fun getLastUsedDevice(): CastDeviceEntity?
}

class AppRepository(private val db: AppDatabase) : Repository {
    private val tabDao = db.tabDao()
    private val historyDao = db.historyDao()
    private val watchEntryDao = db.watchEntryDao()
    private val castDeviceDao = db.castDeviceDao()

    override fun getAllTabs(): Flow<List<TabEntity>> = tabDao.getAllTabs()

    override suspend fun getAllTabsList(): List<TabEntity> = tabDao.getAllTabsList()

    override suspend fun getActiveTab(): TabEntity? = tabDao.getActiveTab()

    override suspend fun saveTab(tab: TabEntity) = tabDao.insertOrUpdate(tab)

    override suspend fun setActiveTab(tabId: String) = tabDao.setActiveTab(tabId)

    override suspend fun deleteTab(tabId: String) = tabDao.deleteTabById(tabId)

    override suspend fun clearTabs() = tabDao.deleteAllTabs()

    override fun getAllHistory(): Flow<List<HistoryVisitEntity>> = historyDao.getAllHistory()

    override fun searchHistory(query: String): Flow<List<HistoryVisitEntity>> = historyDao.searchHistory(query)

    override suspend fun recordVisit(url: String, title: String) {
        val host = try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
        historyDao.insertVisit(
            HistoryVisitEntity(
                url = url,
                title = title.ifBlank { url },
                host = host,
                visitedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteVisit(id: Long) = historyDao.deleteVisitById(id)

    override suspend fun clearHistory() = historyDao.clearAllHistory()

    override fun getContinueWatching(): Flow<List<WatchEntryEntity>> = watchEntryDao.getContinueWatchingEntries()

    override suspend fun getWatchEntryByUrl(pageUrl: String): WatchEntryEntity? =
        watchEntryDao.getEntryByPageUrl(pageUrl)

    override suspend fun recordOrUpdateWatch(
        pageUrl: String,
        title: String,
        host: String,
        seriesKey: String?,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long
    ): Long {
        val existing = watchEntryDao.getEntryByPageUrl(pageUrl)
        val finished = durationMs > 0 && positionMs >= (durationMs * 0.95)
        val now = System.currentTimeMillis()

        val id = if (existing != null) {
            watchEntryDao.updateProgress(
                id = existing.id,
                positionMs = positionMs,
                durationMs = if (durationMs > 0) durationMs else existing.durationMs,
                finished = finished,
                lastWatchedAt = now
            )
            existing.id
        } else {
            watchEntryDao.insertOrUpdate(
                WatchEntryEntity(
                    pageUrl = pageUrl,
                    title = title,
                    host = host,
                    seriesKey = seriesKey,
                    season = season,
                    episode = episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    finished = finished,
                    lastWatchedAt = now
                )
            )
        }

        if (seriesKey != null) {
            watchEntryDao.insertOrUpdateSeries(
                SeriesEntity(
                    seriesKey = seriesKey,
                    displayName = seriesKey,
                    lastEntryId = id,
                    updatedAt = now
                )
            )
        }

        return id
    }

    override suspend fun deleteWatchEntry(id: Long) = watchEntryDao.deleteEntryById(id)

    override fun getSavedDevices(): Flow<List<CastDeviceEntity>> = castDeviceDao.getAllDevices()

    override suspend fun saveDevice(device: CastDeviceEntity) = castDeviceDao.insertOrUpdate(device)

    override suspend fun getLastUsedDevice(): CastDeviceEntity? = castDeviceDao.getLastUsedDevice()
}
