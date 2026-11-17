package com.vkwave.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Device music provider. It reads audio indexed by Android MediaStore and plays
 * content:// URIs directly, without uploading files or depending on a network API.
 */
class LocalAudioSource(context: Context) : MusicSource {
    private val appContext = context.applicationContext

    override val descriptor = SourceDescriptor(
        id = SOURCE_ID,
        displayName = "Музыка на устройстве",
        capabilities = SourceCapabilities(
            search = true,
            library = true,
            offlinePlayback = true
        )
    )

    override suspend fun connectionState(): SourceConnectionState =
        if (hasAudioPermission()) SourceConnectionState.Available
        else SourceConnectionState.NeedsPermission

    override suspend fun search(query: String, pageToken: String?): SourcePage {
        requireAudioPermission()
        val normalized = query.trim()
        if (normalized.isEmpty()) return SourcePage(emptyList())
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (" +
            "${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
            "${MediaStore.Audio.Media.ARTIST} LIKE ?)"
        val pattern = "%$normalized%"
        return SourcePage(queryTracks(selection, arrayOf(pattern, pattern)))
    }

    override suspend fun library(pageToken: String?): SourcePage {
        requireAudioPermission()
        return SourcePage(queryTracks("${MediaStore.Audio.Media.IS_MUSIC} != 0", null))
    }

    override suspend fun resolvePlayback(track: WaveTrack): Uri {
        if (track.sourceId != SOURCE_ID || track.streamUri.isNullOrBlank()) {
            throw UnsupportedSourceTrackException("Track does not belong to the local audio source")
        }
        return Uri.parse(track.streamUri)
    }

    private fun queryTracks(selection: String, selectionArgs: Array<String>?): List<WaveTrack> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val result = mutableListOf<WaveTrack>()
        appContext.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "Без названия"
                val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель"
                val albumId = cursor.getLong(albumColumn)
                val contentUri = ContentUris.withAppendedId(collection, mediaId)
                val artworkUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    albumId
                ).toString()
                result += WaveTrack(
                    id = "$SOURCE_ID:$mediaId",
                    title = title,
                    artist = artist,
                    artworkUri = artworkUri,
                    streamUri = contentUri.toString(),
                    sourceId = SOURCE_ID,
                    providerTrackId = mediaId.toString()
                )
            }
        }
        return result
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requireAudioPermission() {
        if (!hasAudioPermission()) {
            throw SourcePermissionRequiredException(
                "Разреши VK Wave доступ к аудиофайлам устройства"
            )
        }
    }

    companion object {
        const val SOURCE_ID = "local"
    }
}
