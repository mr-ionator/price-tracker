package com.pricetracker.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    companion object {
        private val BACKEND_URL = stringPreferencesKey("backend_url")

        /** Emulator loopback to the host machine; real devices need the LAN IP. */
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8000"
    }

    val backendUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BACKEND_URL] ?: DEFAULT_BACKEND_URL
    }

    suspend fun currentBackendUrl(): String = backendUrl.first()

    suspend fun setBackendUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[BACKEND_URL] = url.trim() }
    }
}
