package com.castbrowser.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tab")
data class TabEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val position: Int,
    val isActive: Boolean,
    val isIncognito: Boolean,
    val lastAccessedAt: Long,
    val stateFile: String? = null,
    val thumbFile: String? = null,
    val faviconFile: String? = null
)
