package com.castbrowser.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesKey: String,
    val displayName: String,
    val lastEntryId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
