package com.vkwave.app

import android.net.Uri

/** Capabilities are declared by a source so the UI can adapt without source-specific checks. */
data class SourceCapabilities(
    val search: Boolean = false,
    val library: Boolean = false,
    val playlists: Boolean = false,
    val authentication: Boolean = false,
    val offlinePlayback: Boolean = false
)

sealed interface SourceConnectionState {
    data object Available : SourceConnectionState
    data object NeedsPermission : SourceConnectionState
    data object NeedsAuthentication : SourceConnectionState
    data class NeedsConfiguration(val reason: String) : SourceConnectionState
    data class Unavailable(val reason: String) : SourceConnectionState
}

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: SourceCapabilities
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class SourcePage(
    val tracks: List<WaveTrack>,
    val nextPageToken: String? = null
)

class SourcePermissionRequiredException(message: String) : Exception(message)
class SourceAuthenticationRequiredException(message: String) : Exception(message)
class UnsupportedSourceTrackException(message: String) : Exception(message)

/**
 * Every catalog integration implements this contract. It returns provider-neutral
 * WaveTrack values; only this source resolves a track into a playable URI.
 */
interface MusicSource {
    val descriptor: SourceDescriptor
    suspend fun connectionState(): SourceConnectionState
    suspend fun search(query: String, pageToken: String? = null): SourcePage
    suspend fun library(pageToken: String? = null): SourcePage
    suspend fun resolvePlayback(track: WaveTrack): Uri
}

/** Explicit registry, injected by the application instead of a global singleton. */
class MusicSourceRegistry {
    private val sources = linkedMapOf<String, MusicSource>()

    @Synchronized
    fun register(source: MusicSource) {
        val id = source.descriptor.id
        require(!sources.containsKey(id)) { "Music source '$id' is already registered" }
        sources[id] = source
    }

    @Synchronized
    fun replace(source: MusicSource) {
        sources[source.descriptor.id] = source
    }

    @Synchronized
    fun unregister(sourceId: String): MusicSource? = sources.remove(sourceId)

    @Synchronized
    fun get(sourceId: String): MusicSource? = sources[sourceId]

    @Synchronized
    fun all(): List<SourceDescriptor> = sources.values.map { it.descriptor }
}
