package com.vkwave.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueTest {
    private val first = WaveTrack("1", "First", "Artist")
    private val second = WaveTrack("2", "Second", "Artist")
    private val third = WaveTrack("3", "Third", "Artist")

    @Test
    fun emptyQueueHasNoCurrentTrack() {
        val queue = PlaybackQueue()
        assertEquals(-1, queue.currentIndex)
        assertNull(queue.current)
        assertNull(queue.next())
    }

    @Test
    fun replaceClampsStartingIndex() {
        val queue = PlaybackQueue()
        queue.replace(listOf(first, second, third), startIndex = 99)
        assertEquals(2, queue.currentIndex)
        assertEquals(third, queue.current)
    }

    @Test
    fun nextStopsAtEndWhenRepeatOff() {
        val queue = PlaybackQueue()
        queue.replace(listOf(first, second))
        assertEquals(second, queue.next())
        assertNull(queue.next())
        assertEquals(second, queue.current)
    }

    @Test
    fun repeatAllWrapsInBothDirections() {
        val queue = PlaybackQueue().apply {
            replace(listOf(first, second))
            repeatMode = QueueRepeatMode.ALL
        }
        assertEquals(second, queue.next())
        assertEquals(first, queue.next())
        assertEquals(second, queue.previous())
    }

    @Test
    fun repeatOneKeepsCurrentTrack() {
        val queue = PlaybackQueue().apply {
            replace(listOf(first, second))
            repeatMode = QueueRepeatMode.ONE
        }
        assertEquals(first, queue.next())
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun selectingInvalidIndexDoesNotChangeCurrentTrack() {
        val queue = PlaybackQueue()
        queue.replace(listOf(first, second))
        assertNull(queue.select(10))
        assertEquals(first, queue.current)
    }
}
