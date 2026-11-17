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

    /** Prevent a late transition from outliving the outgoing track. */
    fun actualFadeDurationMs(remainingMs: Long, configuredSeconds: Int): Long {
        val configuredMs = configuredSeconds.coerceIn(2, 15) * 1_000L
        return remainingMs.coerceAtLeast(1L).coerceAtMost(configuredMs)
    }
}
