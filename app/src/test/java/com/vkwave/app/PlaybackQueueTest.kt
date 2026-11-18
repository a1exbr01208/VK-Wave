package com.vkwave.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueTest {
    private val first = WaveTrack("1", "First", "Artist")
    private val second = WaveTrack("2", "Second", "Artist")
    private val third = WaveTrack("3", "Third", "Artist")

    @Test
    fun resolvedStartIndexSkipsFailedRequestedTrackAndKeepsOriginalOrder() {
        assertEquals(1, resolveQueueStartIndex(listOf(0, 2, 4), requestedIndex = 1))
    }

    @Test
    fun resolvedStartIndexFallsBackToLastEarlierPlayableTrack() {
        assertEquals(1, resolveQueueStartIndex(listOf(0, 1), requestedIndex = 2))
    }

    @Test
    fun resolvedStartIndexClampsNegativeRequestToFirstPlayableTrack() {
        assertEquals(0, resolveQueueStartIndex(listOf(3, 5), requestedIndex = -1))
    }

    @Test
    fun automaticNextSkipsUnavailableItemsAndPreservesOrder() {
        assertEquals(
            listOf(3, 4),
            nextPlayableQueueIndexes(5, 1, QueueRepeatMode.OFF, setOf(2))
        )
    }

    @Test
    fun repeatAllWrapsToEarlierAvailableItems() {
        assertEquals(
            listOf(0, 1),
            nextPlayableQueueIndexes(4, 3, QueueRepeatMode.ALL, setOf(2))
        )
    }

    @Test
    fun repeatOffDoesNotWrapAndRepeatOneDoesNotAdvanceQueue() {
        assertEquals(emptyList<Int>(), nextPlayableQueueIndexes(3, 2, QueueRepeatMode.OFF))
        // Repeat ONE affects automatic advancement, not a manual next command.
        assertEquals(listOf(2), nextPlayableQueueIndexes(3, 1, QueueRepeatMode.ONE))
    }

    @Test
    fun previousSkipsUnavailableItemsAndWrapsOnlyInRepeatAll() {
        assertEquals(
            listOf(0),
            previousPlayableQueueIndexes(4, 3, QueueRepeatMode.OFF, setOf(1, 2))
        )
        assertEquals(
            listOf(0, 3),
            previousPlayableQueueIndexes(4, 1, QueueRepeatMode.ALL, setOf(2))
        )
    }

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
