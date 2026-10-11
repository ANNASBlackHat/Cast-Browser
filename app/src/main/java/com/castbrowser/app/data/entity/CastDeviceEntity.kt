package com.castbrowser.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cast_device")
data class CastDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String, // "DLNA" or "CHROMECAST"
    val address: String,
    val lastUsedAt: Long = System.currentTimeMillis()
)
