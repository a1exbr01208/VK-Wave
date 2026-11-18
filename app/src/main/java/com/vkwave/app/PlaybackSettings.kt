package com.vkwave.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackSettingsDataStore by preferencesDataStore(name = "playback_settings")
private val crossfadeSecondsKey = intPreferencesKey("crossfade_seconds")
private val jamendoClientIdKey = stringPreferencesKey("jamendo_client_id")

val Context.savedCrossfadeSeconds: Flow<Int>
    get() = playbackSettingsDataStore.data.map { preferences ->
        (preferences[crossfadeSecondsKey] ?: 7).coerceIn(2, 15)
    }

suspend fun Context.persistCrossfadeSeconds(seconds: Int) {
    playbackSettingsDataStore.edit { preferences ->
        preferences[crossfadeSecondsKey] = seconds.coerceIn(2, 15)
    }
}

val Context.savedJamendoClientId: Flow<String?>
    get() = playbackSettingsDataStore.data.map { preferences ->
        preferences[jamendoClientIdKey]
    }

suspend fun Context.persistJamendoClientId(clientId: String) {
    playbackSettingsDataStore.edit { preferences ->
        if (clientId.isBlank()) preferences.remove(jamendoClientIdKey)
        else preferences[jamendoClientIdKey] = clientId.trim()
    }
}
