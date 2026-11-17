package com.vkwave.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackSettingsDataStore by preferencesDataStore(name = "playback_settings")
private val crossfadeSecondsKey = intPreferencesKey("crossfade_seconds")

val Context.savedCrossfadeSeconds: Flow<Int>
    get() = playbackSettingsDataStore.data.map { preferences ->
        (preferences[crossfadeSecondsKey] ?: 7).coerceIn(2, 15)
    }

suspend fun Context.persistCrossfadeSeconds(seconds: Int) {
    playbackSettingsDataStore.edit { preferences ->
        preferences[crossfadeSecondsKey] = seconds.coerceIn(2, 15)
    }
}
