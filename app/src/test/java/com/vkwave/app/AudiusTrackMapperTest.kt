package com.vkwave.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiusTrackMapperTest {

    @Test
    fun mapsTrackMetadataArtistArtworkAndStreamEndpoint() {
        val json = JSONObject(
            """
            {
              "id": "track-123",
              "title": "Midnight Signal",
              "user": { "name": "Example Artist", "handle": "example" },
              "artwork": {
                "480x480": "https://cdn.example/cover-large.jpg",
                "150x150": "https://cdn.example/cover-small.jpg"
              },
              "is_streamable": true
            }
            """.trimIndent()
        )

        val track = AudiusTrackMapper.toWaveTrack(json)

        requireNotNull(track)
        assertEquals("audius:track-123", track.id)
        assertEquals("Midnight Signal", track.title)
        assertEquals("Example Artist", track.artist)
        assertEquals("https://cdn.example/cover-large.jpg", track.artworkUri)
        assertEquals("https://api.audius.co/v1/tracks/track-123/stream", track.streamUri)
        assertEquals("audius", track.sourceId)
        assertEquals("track-123", track.providerTrackId)
    }

    @Test
    fun usesConfiguredApiBaseUrlForStreamEndpoint() {
        val json = JSONObject(
            """{"id":"track-custom","title":"Custom endpoint"}"""
        )

        val track = AudiusTrackMapper.toWaveTrack(
            json,
            apiBaseUrl = "https://gateway.example/v1/"
        )

        requireNotNull(track)
        assertEquals(
            "https://gateway.example/v1/tracks/track-custom/stream",
            track.streamUri
        )
    }

    @Test
    fun buildsStreamUrlWithoutDuplicateSlashWhenApiBaseHasTrailingSlash() {
        assertEquals(
            "https://gateway.example/v1/tracks/track-1/stream",
            audiusStreamUrl("https://gateway.example/v1/", "track-1")
        )
    }

    @Test
    fun fallsBackToHandleAndSmallArtwork() {
        val json = JSONObject(
            """
            {
              "id": "track-456",
              "title": "Afterglow",
              "user": { "handle": "dj-example" },
              "artwork": { "150x150": "https://cdn.example/small.jpg" }
            }
            """.trimIndent()
        )

        val track = AudiusTrackMapper.toWaveTrack(json)

        requireNotNull(track)
        assertEquals("dj-example", track.artist)
        assertEquals("https://cdn.example/small.jpg", track.artworkUri)
    }

    @Test
    fun rejectsMissingIdentityTitleAndNonStreamableTracks() {
        assertNull(AudiusTrackMapper.toWaveTrack(JSONObject("""{"title":"No ID"}""")))
        assertNull(AudiusTrackMapper.toWaveTrack(JSONObject("""{"id":"track-1"}""")))
        assertNull(
            AudiusTrackMapper.toWaveTrack(
                JSONObject("""{"id":"track-2","title":"Unavailable","is_streamable":false}""")
            )
        )
    }

    @Test
    fun rejectsTrackIdsThatCouldAlterTheStreamPath() {
        listOf("../other", "track/other", "track?redirect=example", "track#fragment")
            .forEach { id ->
                assertNull(AudiusTrackMapper.toWaveTrack(
                    JSONObject(mapOf("id" to id, "title" to "Unsafe"))
                ))
            }
    }

    @Test
    fun ignoresInvalidArtworkAndUsesFallbackArtist() {
        val json = JSONObject(
            """{"id":"track-789","title":"Untitled","user":{},"artwork":{"480x480":"javascript:bad"}}"""
        )

        val track = AudiusTrackMapper.toWaveTrack(json)

        requireNotNull(track)
        assertEquals("Неизвестный исполнитель", track.artist)
        assertNull(track.artworkUri)
    }
}
