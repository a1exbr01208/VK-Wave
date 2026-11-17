package com.vkwave.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Official Audius REST API adapter.
 *
 * Read-only search and streaming endpoints are available without a user session.
 * The stream endpoint returns/redirects to the playable audio resource; ExoPlayer
 * follows the redirect. An API key can be added later for higher rate limits,
 * but no secret is required for the initial integration.
 */
class AudiusMusicSource(
    private val apiBaseUrl: String = API_BASE_URL,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 20_000
) : MusicSource {

    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "Audius",
        capabilities = SourceCapabilities(
            search = true,
            library = false,
            playlists = false,
            authentication = false,
            offlinePlayback = false
        )
    )

    override suspend fun connectionState(): SourceConnectionState =
        SourceConnectionState.Available

    override suspend fun search(query: String, pageToken: String?): SourcePage =
        withContext(Dispatchers.IO) {
            val normalized = query.trim()
            if (normalized.isEmpty()) return@withContext SourcePage(emptyList())

            val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val encodedQuery = URLEncoder.encode(normalized, Charsets.UTF_8.name())
            val payload = getJson(
                "/tracks/search?query=$encodedQuery&limit=$PAGE_SIZE&offset=$offset"
            )
            val data = payload.optJSONArray("data") ?: return@withContext SourcePage(emptyList())
            val tracks = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val track = item.toWaveTrackOrNull() ?: continue
                    add(track)
                }
            }
            val nextOffset = if (data.length() >= PAGE_SIZE) (offset + data.length()).toString() else null
            SourcePage(tracks, nextOffset)
        }

    override suspend fun library(pageToken: String?): SourcePage = SourcePage(emptyList())

    override suspend fun resolvePlayback(track: WaveTrack): Uri {
        if (track.sourceId != SOURCE_ID || track.providerTrackId.isBlank()) {
            throw UnsupportedSourceTrackException("Track does not belong to Audius")
        }
        return Uri.parse("$apiBaseUrl/tracks/${track.providerTrackId}/stream")
    }

    private fun getJson(path: String): JSONObject {
        val connection = (URL(apiBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "VK-Wave-Android")
            instanceFollowRedirects = true
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Audius API returned HTTP $status")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toWaveTrackOrNull(): WaveTrack? {
        val trackId = optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val user = optJSONObject("user")
        val artist = user?.optString("name")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: user?.optString("handle")?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Неизвестный исполнитель"
        val artwork = optJSONObject("artwork")
        val artworkUrl = artwork?.optString("480x480")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: artwork?.optString("150x150")
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val streamable = optBoolean("is_streamable", true)
        if (!streamable) return null

        return WaveTrack(
            id = "$SOURCE_ID:$trackId",
            title = title,
            artist = artist,
            artworkUri = artworkUrl,
            streamUri = "$apiBaseUrl/tracks/$trackId/stream",
            sourceId = SOURCE_ID,
            providerTrackId = trackId
        )
    }

    companion object {
        const val SOURCE_ID = "audius"
        private const val API_BASE_URL = "https://api.audius.co/v1"
        private const val PAGE_SIZE = 20
    }
}
