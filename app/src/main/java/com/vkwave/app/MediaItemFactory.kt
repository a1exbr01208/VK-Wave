package com.vkwave.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Converts a resolved provider track into the single queue format used by Media3. */
fun WaveTrack.toMediaItem(resolvedUri: Uri): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .build()
    return MediaItem.Builder()
        .setMediaId("$sourceId:$providerTrackId")
        .setUri(resolvedUri)
        .setMediaMetadata(metadata)
        .build()
}
