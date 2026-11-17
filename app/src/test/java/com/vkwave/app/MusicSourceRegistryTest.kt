package com.vkwave.app

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MusicSourceRegistryTest {
    private class FakeSource(override val descriptor: SourceDescriptor) : MusicSource {
        override suspend fun connectionState() = SourceConnectionState.Available
        override suspend fun search(query: String, pageToken: String?) = SourcePage(emptyList())
        override suspend fun library(pageToken: String?) = SourcePage(emptyList())
        override suspend fun resolvePlayback(track: WaveTrack) = Uri.parse("https://example.invalid/audio")
    }

    @Test
    fun registersAndFindsSourcesByStableId() {
        val registry = MusicSourceRegistry()
        val source = FakeSource(SourceDescriptor("test", "Test source", SourceCapabilities(search = true)))
        registry.register(source)

        assertNotNull(registry.get("test"))
        assertEquals(listOf(source.descriptor), registry.all())
    }

    @Test
    fun rejectsDuplicateIds() {
        val registry = MusicSourceRegistry()
        val descriptor = SourceDescriptor("test", "Test source", SourceCapabilities())
        registry.register(FakeSource(descriptor))

        try {
            registry.register(FakeSource(descriptor))
            throw AssertionError("Duplicate source id should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun replaceAndUnregisterWork() {
        val registry = MusicSourceRegistry()
        val first = FakeSource(SourceDescriptor("test", "First", SourceCapabilities()))
        val replacement = FakeSource(SourceDescriptor("test", "Replacement", SourceCapabilities()))
        registry.register(first)
        registry.replace(replacement)

        assertEquals("Replacement", registry.get("test")?.descriptor?.displayName)
        assertNotNull(registry.unregister("test"))
        assertNull(registry.get("test"))
    }
}
