package com.vkwave.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Equal-power gain curve for a crossfade. Progress is clamped to 0..1. */
object CrossfadeMath {
    fun outgoingGain(progress: Float): Float {
        val angle = progress.coerceIn(0f, 1f) * PI / 2.0
        return cos(angle).toFloat()
    }

    fun incomingGain(progress: Float): Float {
        val angle = progress.coerceIn(0f, 1f) * PI / 2.0
        return sin(angle).toFloat()
    }
}
