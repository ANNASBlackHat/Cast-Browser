package com.castbrowser.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_entry",
    indices = [
        Index("pageUrl"),
        Index("seriesKey"),
        Index("lastWatchedAt")
    ]
)
data class WatchEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageUrl: String,
    val title: String,
    val host: String,
    val seriesKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val finished: Boolean = false,
    val lastWatchedAt: Long = System.currentTimeMillis()
)
