package com.vkwave.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternetArchiveTrackMapperTest {

    @Test
    fun mapsPublicDomainAudioAndPrefersMp3() {
        val metadata = JSONObject(
            """
            {
              "title": "Old Radio Jazz",
              "creator": ["Example Orchestra"],
              "licenseurl": "https://creativecommons.org/publicdomain/mark/1.0/"
            }
            """.trimIndent()
        )
        val files = JSONArray(
            """
            [
              {"name":"recording.flac","format":"Flac"},
              {"name":"recording.mp3","format":"MP3"},
              {"name":"cover.jpg","format":"JPEG"}
            ]
            """.trimIndent()
        )

        val track = InternetArchiveTrackMapper.toWaveTrack("archive-item", metadata, files)

        requireNotNull(track)
        assertEquals("internet_archive:archive-item:recording.mp3", track.id)
        assertEquals("Old Radio Jazz", track.title)
        assertEquals("Example Orchestra", track.artist)
        assertEquals(
            "https://archive.org/download/archive-item/recording.mp3",
            track.streamUri
        )
        assertEquals(
            "https://archive.org/services/img/archive-item",
            track.artworkUri
        )
    }

    @Test
    fun acceptsCc0AndEncodesFileNames() {
        val metadata = JSONObject(
            """{"title":"Free Recording","licenseurl":"https://creativecommons.org/publicdomain/zero/1.0/"}"""
        )
        val files = JSONArray("""[{"name":"folder/recording one.mp3"}]""")

        val track = InternetArchiveTrackMapper.toWaveTrack(
            "item-2",
            metadata,
            files,
            baseUrl = "https://archive.org"
        )

        requireNotNull(track)
        assertEquals(
            "https://archive.org/download/item-2/folder/recording%20one.mp3",
            track.streamUri
        )
        assertEquals("Неизвестный исполнитель", track.artist)
    }

    @Test
    fun rejectsOtherLicensesAndItemsWithoutPlayableAudio() {
        val byLicense = JSONObject(
            """{"title":"Attribution Required","licenseurl":"https://creativecommons.org/licenses/by/4.0/"}"""
        )
        val noAudio = JSONObject(
            """{"title":"No Audio","licenseurl":"https://creativecommons.org/publicdomain/zero/1.0/"}"""
        )

        assertNull(
            InternetArchiveTrackMapper.toWaveTrack(
                "by-item",
                byLicense,
                JSONArray("""[{"name":"track.mp3"}]""")
            )
        )
        assertNull(
            InternetArchiveTrackMapper.toWaveTrack(
                "text-item",
                noAudio,
                JSONArray("""[{"name":"cover.jpg"},{"name":"metadata.xml"}]""")
            )
        )
    }
}
