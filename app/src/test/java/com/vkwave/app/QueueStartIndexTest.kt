package com.vkwave.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueStartIndexTest {

    @Test
    fun selectsTheRequestedTrackWhenItResolved() {
        assertEquals(1, resolveQueueStartIndex(listOf(0, 1, 2), 1))
    }

    @Test
    fun selectsNextPlayableTrackWhenRequestedTrackFailed() {
        assertEquals(1, resolveQueueStartIndex(listOf(0, 3, 4), 2))
    }

    @Test
    fun fallsBackToLastEarlierPlayableTrackWhenNothingAfterSelectionResolved() {
        assertEquals(1, resolveQueueStartIndex(listOf(0, 1), 2))
    }

    @Test
    fun returnsNullWhenNoTracksResolved() {
        assertNull(resolveQueueStartIndex(emptyList(), 2))
    }

    @Test
    fun negativeRequestedIndexStartsFromFirstAvailableTrack() {
        assertEquals(0, resolveQueueStartIndex(listOf(2, 4), -1))
    }
}
