package com.vkwave.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Equal-power gain curve and timing helpers for crossfades. */
object CrossfadeMath {
    fun outgoingGain(progress: Float): Float {
        val angle = progress.coerceIn(0f, 1f) * PI / 2.0
        return cos(angle).toFloat()
    }

    fun incomingGain(progress: Float): Float {
        val angle = progress.coerceIn(0f, 1f) * PI / 2.0
        return sin(angle).toFloat()
    }

    /** Combines track fade, user volume and temporary system audio-focus ducking. */
    fun outputVolume(deckGain: Float, userVolume: Float, focusGain: Float): Float =
        deckGain.coerceIn(0f, 1f) *
            userVolume.coerceIn(0f, 1f) *
            focusGain.coerceIn(0f, 1f)

    /** Prevent a late transition from outliving either participating track. */
    fun actualFadeDurationMs(remainingMs: Long, configuredSeconds: Int): Long {
        val configuredMs = configuredSeconds.coerceIn(2, 15) * 1_000L
        return remainingMs.coerceAtLeast(1L).coerceAtMost(configuredMs)
    }

    /** A short incoming track must not be faded for longer than it can play. */
    fun capFadeToIncomingDuration(requestedFadeMs: Long, incomingDurationMs: Long): Long {
        val requested = requestedFadeMs.coerceAtLeast(1L)
        return if (incomingDurationMs > 0L) minOf(requested, incomingDurationMs) else requested
    }
}
