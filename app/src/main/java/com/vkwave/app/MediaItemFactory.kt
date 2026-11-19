package com.vkwave.app

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

const val WAVE_DURATION_EXTRA = "com.vkwave.app.DURATION_MS"

/** Converts a resolved provider track into the single queue format used by Media3. */
fun WaveTrack.toMediaItem(resolvedUri: Uri): MediaItem {
    val metadataBuilder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setArtworkUri(artworkUri?.let(Uri::parse))
    durationMs?.takeIf { it > 0L }?.let { duration ->
        metadataBuilder.setExtras(Bundle().apply {
            putLong(WAVE_DURATION_EXTRA, duration)
        })
    }
    val metadata = metadataBuilder.build()
    return MediaItem.Builder()
        .setMediaId("$sourceId:$providerTrackId")
        .setUri(resolvedUri)
        .setMediaMetadata(metadata)
        .build()
}
