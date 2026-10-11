package com.castbrowser.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.castbrowser.app.data.dao.*
import com.castbrowser.app.data.entity.*

@Database(
    entities = [
        TabEntity::class,
        HistoryVisitEntity::class,
        WatchEntryEntity::class,
        SeriesEntity::class,
        BookmarkEntity::class,
        CastDeviceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun historyDao(): HistoryDao
    abstract fun watchEntryDao(): WatchEntryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun castDeviceDao(): CastDeviceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cast_browser.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
