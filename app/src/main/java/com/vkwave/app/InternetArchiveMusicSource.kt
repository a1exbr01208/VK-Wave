package com.vkwave.app

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet Archive adapter restricted to items marked Public Domain or CC0.
 *
 * Search uses the public Advanced Search API. Each result's metadata is checked
 * again before a playable audio file is exposed, so items with other licenses
 * are never silently treated as free-to-use.
 */
class InternetArchiveMusicSource(
    private val baseUrl: String = BASE_URL,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 20_000
) : MusicSource {

    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "Internet Archive · Public Domain",
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

            val page = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val phrase = escapeLucenePhrase(normalized)
            val licenseFilter =
                "(licenseurl:http*creativecommons.org/publicdomain/zero/1.0* OR " +
                    "licenseurl:http*creativecommons.org/publicdomain/mark/1.0*)"
            val luceneQuery =
                "mediatype:audio AND $licenseFilter AND (title:\"$phrase\" OR creator:\"$phrase\")"
            val payload = getJson(
                "/advancedsearch.php?q=${encode(luceneQuery)}" +
                    "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl" +
                    "&rows=$PAGE_SIZE&page=$page&output=json"
            )
            val response = payload.optJSONObject("response")
                ?: return@withContext SourcePage(emptyList())
            val docs = response.optJSONArray("docs") ?: JSONArray()
            val semaphore = Semaphore(MAX_METADATA_REQUESTS)
            val tracks = coroutineScope {
                (0 until docs.length()).mapNotNull { index ->
                    docs.optJSONObject(index)
                }.map { doc ->
                    async {
                        semaphore.withPermit {
                            val identifier = doc.optString("identifier")
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?: return@withPermit null
                            try {
                                val metadataPayload = getJson("/metadata/${encodePathSegment(identifier)}")
                                val metadata = metadataPayload.optJSONObject("metadata") ?: JSONObject()
                                val files = metadataPayload.optJSONArray("files") ?: JSONArray()
                                InternetArchiveTrackMapper.toWaveTrack(identifier, metadata, files, baseUrl)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.id }
            }

            val total = response.optInt("numFound", 0)
            val nextPage = if (page * PAGE_SIZE < total) (page + 1).toString() else null
            SourcePage(tracks, nextPage)
        }

    override suspend fun library(pageToken: String?): SourcePage =
        SourcePage(emptyList())

    override suspend fun resolvePlayback(track: WaveTrack): Uri {
        if (track.sourceId != SOURCE_ID || track.streamUri.isNullOrBlank()) {
            throw UnsupportedSourceTrackException("Track does not belong to Internet Archive")
        }
        return Uri.parse(track.streamUri)
    }

    private fun getJson(path: String): JSONObject {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
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
                throw IOException("Internet Archive API returned HTTP $status")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodePathSegment(value: String): String =
        encode(value).replace("+", "%20")

    private fun escapeLucenePhrase(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val SOURCE_ID = "internet_archive"
        private const val BASE_URL = "https://archive.org"
        private const val PAGE_SIZE = 10
        private const val MAX_METADATA_REQUESTS = 4
    }
}

/** Pure metadata mapper. Only Public Domain Mark and CC0 items are accepted. */
internal object InternetArchiveTrackMapper {
    private const val SOURCE_ID = InternetArchiveMusicSource.SOURCE_ID

    fun toWaveTrack(
        identifier: String,
        metadata: JSONObject,
        files: JSONArray,
        baseUrl: String = "https://archive.org"
    ): WaveTrack? {
        if (!isSafePathSegment(identifier) || !isAllowedLicense(metadata.optString("licenseurl"))) return null

        val title = metadata.optString("title")
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val creator = firstString(metadata.opt("creator"))
            ?: firstString(metadata.opt("contributor"))
            ?: "Неизвестный исполнитель"

        val playableFile = (0 until files.length())
            .mapNotNull { files.optJSONObject(it) }
            .mapNotNull { file ->
                val name = file.optString("name").takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val extension = name.substringAfterLast('.', "").lowercase()
                val priority = AUDIO_EXTENSION_PRIORITY[extension] ?: return@mapNotNull null
                Triple(priority, name, file)
            }
            .minByOrNull { it.first }
            ?: return null

        val fileName = playableFile.second
        val pathSegments = fileName.split('/')
        if (pathSegments.any { !isSafePathSegment(it) }) return null
        val encodedFileName = pathSegments.joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        val encodedIdentifier = URLEncoder.encode(identifier, Charsets.UTF_8.name()).replace("+", "%20")
        val streamUrl = "${baseUrl.trimEnd('/')}/download/$encodedIdentifier/$encodedFileName"

        return WaveTrack(
            id = "$SOURCE_ID:$identifier:$fileName",
            title = title,
            artist = creator,
            artworkUri = "${baseUrl.trimEnd('/')}/services/img/$encodedIdentifier",
            streamUri = streamUrl,
            sourceId = SOURCE_ID,
            providerTrackId = "$identifier/$fileName"
        )
    }

    private fun isSafePathSegment(value: String): Boolean =
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            value.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }

    private fun isAllowedLicense(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.contains("creativecommons.org/publicdomain/zero/1.0") ||
            normalized.contains("creativecommons.org/publicdomain/mark/1.0")
    }

    private fun firstString(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() && it != "null" }
        is JSONArray -> value.optString(0).takeIf { it.isNotBlank() && it != "null" }
        else -> null
    }

    private val AUDIO_EXTENSION_PRIORITY = mapOf(
        "mp3" to 0,
        "m4a" to 1,
        "aac" to 2,
        "ogg" to 3,
        "opus" to 4,
        "flac" to 5,
        "wav" to 6
    )
}
