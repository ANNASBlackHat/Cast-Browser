package com.castbrowser.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_settings")

class UserSettings(private val context: Context) {
    companion object {
        val KEY_DEFAULT_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val KEY_AD_BLOCK_ENABLED = booleanPreferencesKey("ad_block_enabled")
        val KEY_CLEAR_ON_EXIT = booleanPreferencesKey("clear_on_exit")
    }

    val defaultSearchEngine: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_SEARCH_ENGINE] ?: "https://www.google.com/search?q="
    }

    val adBlockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AD_BLOCK_ENABLED] ?: true
    }

    suspend fun setSearchEngine(engine: String) {
        context.dataStore.edit { it[KEY_DEFAULT_SEARCH_ENGINE] = engine }
    }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AD_BLOCK_ENABLED] = enabled }
    }
}
