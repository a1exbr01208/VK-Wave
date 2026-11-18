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
 * Official Jamendo API v3 adapter.
 *
 * Jamendo requires an application client_id. It can be supplied from local.properties
 * for development or configured by the user in Settings. It is a public app identifier.
 */
class JamendoMusicSource(
    clientId: String = BuildConfig.JAMENDO_CLIENT_ID,
    private val apiBaseUrl: String = API_BASE_URL,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 20_000
) : MusicSource {

    @Volatile
    private var clientId: String = clientId

    val configuredClientId: String
        get() = clientId

    fun setClientId(value: String) {
        clientId = value.trim()
    }

    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "Jamendo",
        capabilities = SourceCapabilities(
            search = true,
            library = false,
            playlists = false,
            authentication = false,
            offlinePlayback = false
        )
    )

    override suspend fun connectionState(): SourceConnectionState =
        if (clientId.isBlank()) {
            SourceConnectionState.NeedsConfiguration(
                "Jamendo: укажи публичный Client ID в настройках приложения"
            )
        } else {
            SourceConnectionState.Available
        }

    override suspend fun search(query: String, pageToken: String?): SourcePage =
        withContext(Dispatchers.IO) {
            ensureConfigured()
            val normalized = query.trim()
            if (normalized.isEmpty()) return@withContext SourcePage(emptyList())

            val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val encodedQuery = URLEncoder.encode(normalized, Charsets.UTF_8.name())
            val payload = getJson(
                "/tracks/?client_id=${encode(clientId)}&format=json&limit=$PAGE_SIZE&offset=$offset" +
                    "&search=$encodedQuery&audioformat=mp32&imagesize=300&type=single+albumtrack"
            )
            val headers = payload.optJSONObject("headers")
            if (headers?.optString("status") == "error") {
                throw IOException(headers.optString("error_message").ifBlank { "Jamendo API error" })
            }
            val results = payload.optJSONArray("results") ?: return@withContext SourcePage(emptyList())
            val tracks = buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val track = JamendoTrackMapper.toWaveTrack(item) ?: continue
                    add(track)
                }
            }
            val nextOffset = if (results.length() >= PAGE_SIZE) (offset + results.length()).toString() else null
            SourcePage(tracks, nextOffset)
        }

    override suspend fun library(pageToken: String?): SourcePage = SourcePage(emptyList())

    override suspend fun resolvePlayback(track: WaveTrack): Uri {
        ensureConfigured()
        if (track.sourceId != SOURCE_ID || track.providerTrackId.isBlank()) {
            throw UnsupportedSourceTrackException("Track does not belong to Jamendo")
        }
        val directStream = track.streamUri?.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }
        return Uri.parse(
            directStream ?: (
                "$apiBaseUrl/tracks/file/?client_id=${encode(clientId)}" +
                    "&id=${encode(track.providerTrackId)}&action=stream&audioformat=mp32"
                )
        )
    }

    private fun ensureConfigured() {
        if (clientId.isBlank()) {
            throw IllegalStateException("Jamendo не настроен: укажи Client ID в настройках приложения")
        }
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
            if (status !in 200..299) throw IOException("Jamendo API returned HTTP $status")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val SOURCE_ID = "jamendo"
        private const val API_BASE_URL = "https://api.jamendo.com/v3.0"
        private const val PAGE_SIZE = 20
    }
}

/** Pure JSON-to-domain mapping, kept separate so provider responses can be unit-tested. */
internal object JamendoTrackMapper {
    fun toWaveTrack(item: JSONObject): WaveTrack? {
        val trackId = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = item.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val artist = item.optString("artist_name")
            .takeIf { it.isNotBlank() && it != "null" } ?: "Неизвестный исполнитель"
        val artwork = item.optString("image")
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val stream = item.optString("audio")
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }

        return WaveTrack(
            id = "${JamendoMusicSource.SOURCE_ID}:$trackId",
            title = title,
            artist = artist,
            artworkUri = artwork,
            streamUri = stream,
            sourceId = JamendoMusicSource.SOURCE_ID,
            providerTrackId = trackId
        )
    }
}
