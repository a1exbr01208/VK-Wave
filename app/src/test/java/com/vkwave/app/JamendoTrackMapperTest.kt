package com.vkwave.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class JamendoTrackMapperTest {

    @Test
    fun runtimeClientIdConfigurationUpdatesConnectionState() = runBlocking {
        val source = JamendoMusicSource(clientId = "")
        assertEquals(
            true,
            source.connectionState() is SourceConnectionState.NeedsConfiguration
        )

        source.setClientId("public-client-id")
        assertEquals(SourceConnectionState.Available, source.connectionState())
        source.setClientId("")
        assertEquals(
            true,
            source.connectionState() is SourceConnectionState.NeedsConfiguration
        )
    }


    @Test
    fun buildsFallbackStreamUrlWithoutDuplicateSlash() {
        assertEquals(
            "https://api.example/v3.0/tracks/file/?client_id=public-id&id=42&action=stream&audioformat=mp32",
            jamendoStreamUrl("https://api.example/v3.0/", "public-id", "42")
        )
    }

    @Test
    fun mapsTrackMetadataAndArtwork() {
        val json = JSONObject(
            """
            {
              "id": "98765",
              "name": "Night Drive",
              "artist_name": "Example Artist",
              "image": "https://usercontent.jamendo.com?image=cover",
              "audio": "https://prod-1.storage.jamendo.com/?trackid=98765"
            }
            """.trimIndent()
        )

        val track = JamendoTrackMapper.toWaveTrack(json)

        requireNotNull(track)
        assertEquals("jamendo:98765", track.id)
        assertEquals("Night Drive", track.title)
        assertEquals("Example Artist", track.artist)
        assertEquals("https://usercontent.jamendo.com?image=cover", track.artworkUri)
        assertEquals("https://prod-1.storage.jamendo.com/?trackid=98765", track.streamUri)
        assertEquals("jamendo", track.sourceId)
        assertEquals("98765", track.providerTrackId)
    }

    @Test
    fun rejectsItemsWithoutRequiredIdentityOrTitle() {
        assertNull(JamendoTrackMapper.toWaveTrack(JSONObject("""{"name":"No ID"}""")))
        assertNull(JamendoTrackMapper.toWaveTrack(JSONObject("""{"id":"123"}""")))
    }

    @Test
    fun usesFallbackArtistAndIgnoresInvalidArtwork() {
        val json = JSONObject(
            """{"id":"42","name":"Untitled","artist_name":"","image":"javascript:bad"}"""
        )

        val track = JamendoTrackMapper.toWaveTrack(json)

        requireNotNull(track)
        assertEquals("Неизвестный исполнитель", track.artist)
        assertNull(track.artworkUri)
    }
}
