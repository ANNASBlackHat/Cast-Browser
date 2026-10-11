package com.castbrowser.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history_visit",
    indices = [
        Index("visitedAt"),
        Index("host")
    ]
)
data class HistoryVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val host: String,
    val visitedAt: Long
)
