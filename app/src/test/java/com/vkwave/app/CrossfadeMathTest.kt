package com.vkwave.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeMathTest {
    @Test
    fun startsWithOutgoingTrackOnly() {
        assertEquals(1f, CrossfadeMath.outgoingGain(0f), 0.0001f)
        assertEquals(0f, CrossfadeMath.incomingGain(0f), 0.0001f)
    }

    @Test
    fun endsWithIncomingTrackOnly() {
        assertEquals(0f, CrossfadeMath.outgoingGain(1f), 0.0001f)
        assertEquals(1f, CrossfadeMath.incomingGain(1f), 0.0001f)
    }

    @Test
    fun midpointHasEqualPowerGains() {
        val outgoing = CrossfadeMath.outgoingGain(0.5f)
        val incoming = CrossfadeMath.incomingGain(0.5f)
        assertEquals(outgoing, incoming, 0.0001f)
        assertEquals(1f, outgoing * outgoing + incoming * incoming, 0.0001f)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(1f, CrossfadeMath.outgoingGain(-1f), 0.0001f)
        assertEquals(1f, CrossfadeMath.incomingGain(2f), 0.0001f)
    }

    @Test
    fun systemDuckingScalesButDoesNotReplaceUserVolume() {
        assertEquals(0.15f, CrossfadeMath.outputVolume(1f, 0.75f, 0.2f), 0.0001f)
        assertEquals(0.75f, CrossfadeMath.outputVolume(1f, 0.75f, 1f), 0.0001f)
    }

    @Test
    fun outputVolumeClampsAllInputs() {
        assertEquals(0f, CrossfadeMath.outputVolume(-1f, 0.8f, 1f), 0.0001f)
        assertEquals(0.8f, CrossfadeMath.outputVolume(2f, 0.8f, 2f), 0.0001f)
    }

    @Test
    fun lateFadeNeverExceedsRemainingTrackTime() {
        assertEquals(2_300L, CrossfadeMath.actualFadeDurationMs(2_300L, 7))
        assertEquals(7_000L, CrossfadeMath.actualFadeDurationMs(9_000L, 7))
    }

    @Test
    fun shortIncomingTrackCapsCrossfadeDuration() {
        assertEquals(900L, CrossfadeMath.capFadeToIncomingDuration(7_000L, 900L))
        assertEquals(2_300L, CrossfadeMath.capFadeToIncomingDuration(2_300L, 5_000L))
        // Unknown/live durations must not collapse the fade to zero.
        assertEquals(7_000L, CrossfadeMath.capFadeToIncomingDuration(7_000L, -1L))
        assertEquals(1L, CrossfadeMath.capFadeToIncomingDuration(0L, 0L))
    }

    @Test
    fun fadeDurationRespectsSupportedBounds() {
        assertEquals(2_000L, CrossfadeMath.actualFadeDurationMs(9_000L, 1))
        assertEquals(15_000L, CrossfadeMath.actualFadeDurationMs(20_000L, 20))
        assertEquals(1L, CrossfadeMath.actualFadeDurationMs(0L, 7))
    }
}
