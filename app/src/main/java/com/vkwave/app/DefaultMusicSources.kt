package com.vkwave.app

import android.content.Context

/**
 * Creates the default provider set for the app.
 *
 * Providers are registered once at the application boundary. UI and playback
 * code consume MusicSourceRegistry and never branch on provider implementations.
 */
fun createMusicSourceRegistry(context: Context): MusicSourceRegistry =
    MusicSourceRegistry().apply {
        register(LocalAudioSource(context))
        register(AudiusMusicSource())
        register(InternetArchiveMusicSource())
        register(JamendoMusicSource())
    }
