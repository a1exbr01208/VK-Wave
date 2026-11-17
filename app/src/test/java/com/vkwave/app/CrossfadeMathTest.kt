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
}
