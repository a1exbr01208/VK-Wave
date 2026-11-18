package com.vkwave.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MusicSourceRegistryTest {
    private class FakeSource(
        override val descriptor: SourceDescriptor
    ) : MusicSource {
        override suspend fun connectionState() = SourceConnectionState.Available
        override suspend fun search(query: String, pageToken: String?) = SourcePage(emptyList())
        override suspend fun library(pageToken: String?) = SourcePage(emptyList())
        override suspend fun resolvePlayback(track: WaveTrack) =
            Uri.parse("https://example.invalid/audio")
    }

    private fun source(id: String, name: String = id) = FakeSource(
        SourceDescriptor(id, name, SourceCapabilities(search = true))
    )

    @Test
    fun registerKeepsInsertionOrderAndRejectsDuplicateIds() {
        val registry = MusicSourceRegistry()
        val audius = source("audius")
        val jamendo = source("jamendo")
        registry.register(audius)
        registry.register(jamendo)

        assertSame(audius, registry.get("audius"))
        assertEquals(listOf("audius", "jamendo"), registry.all().map { it.id })
        try {
            registry.register(source("audius", "Duplicate"))
            throw AssertionError("Duplicate source id should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun replaceUpdatesExistingSourceWithoutChangingItsPosition() {
        val registry = MusicSourceRegistry()
        registry.register(source("audius"))
        registry.register(source("jamendo"))
        val replacement = source("audius", "Audius replacement")

        registry.replace(replacement)

        assertSame(replacement, registry.get("audius"))
        assertEquals(listOf("audius", "jamendo"), registry.all().map { it.id })
        assertEquals("Audius replacement", registry.all().first().displayName)
    }

    @Test
    fun unregisterReturnsRemovedSourceAndMissingIdsReturnNull() {
        val registry = MusicSourceRegistry()
        val audius = source("audius")
        registry.register(audius)

        assertSame(audius, registry.unregister("audius"))
        assertNull(registry.get("audius"))
        assertNull(registry.unregister("missing"))
    }
}
