package com.vkwave.app

/**
 * Provider-neutral track model. A source owns the meaning of providerTrackId
 * and resolves the playable URI; the queue and playback engine stay agnostic.
 */
data class WaveTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUri: String? = null,
    val streamUri: String? = null,
    val sourceId: String = "unknown",
    val providerTrackId: String = id,
    val album: String? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val year: Int? = null
) {
    init {
        require(id.isNotBlank()) { "Track id must not be blank" }
        require(title.isNotBlank()) { "Track title must not be blank" }
        require(artist.isNotBlank()) { "Track artist must not be blank" }
        require(sourceId.isNotBlank()) { "Source id must not be blank" }
        require(providerTrackId.isNotBlank()) { "Provider track id must not be blank" }
    }

    val isPlayable: Boolean
        get() = !streamUri.isNullOrBlank()
}
