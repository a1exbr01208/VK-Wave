package com.vkwave.app

/**
 * Provider-neutral track model.
 *
 * streamUri must be supplied by an authorized provider integration; the UI
 * and queue layer never manufacture or scrape playback URLs.
 */
data class WaveTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUri: String? = null,
    val streamUri: String? = null
) {
    init {
        require(id.isNotBlank()) { "Track id must not be blank" }
        require(title.isNotBlank()) { "Track title must not be blank" }
        require(artist.isNotBlank()) { "Track artist must not be blank" }
    }

    val isPlayable: Boolean
        get() = !streamUri.isNullOrBlank()
}
