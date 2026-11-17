package com.vkwave.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** Dual-player equal-power crossfade core. Requires authorized playable media URIs. */
class CrossfadeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main)
    private val players = arrayOf(ExoPlayer.Builder(appContext).build(), ExoPlayer.Builder(appContext).build())
    private var active = 0
    private var transitionJob: Job? = null
    private var monitorJob: Job? = null
    private var playlist: List<Uri> = emptyList()
    private var index = 0
    var crossfadeSeconds: Int = 7
        set(value) { field = value.coerceIn(2, 15) }

    fun setQueue(items: List<Uri>, startIndex: Int = 0) {
        playlist = items
        index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (playlist.isEmpty()) return
        players[active].setMediaItem(MediaItem.fromUri(playlist[index]))
        players[active].prepare()
        players[active].volume = 1f
        startMonitor()
    }
    fun play() { players[active].play() }
    fun pause() { players[active].pause() }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val p = players[active]
                val duration = p.duration
                if (p.isPlaying && duration > 0 && transitionJob == null) {
                    val remaining = duration - p.currentPosition
                    val next = (index + 1) % playlist.size
                    val fadeMs = crossfadeSeconds * 1000L
                    if (playlist.size > 1 && remaining <= fadeMs) beginCrossfade(next, fadeMs)
                }
                delay(100)
            }
        }
    }

    private fun beginCrossfade(nextIndex: Int, durationMs: Long) {
        val outgoing = players[active]
        val incoming = players[1 - active]
        transitionJob = scope.launch {
            incoming.stop()
            incoming.clearMediaItems()
            incoming.setMediaItem(MediaItem.fromUri(playlist[nextIndex]))
            incoming.volume = 0f
            incoming.prepare()
            while (incoming.playbackState != Player.STATE_READY && isActive) delay(20)
            incoming.play()
            val steps = (durationMs / 40).coerceAtLeast(1)
            repeat(steps.toInt()) { step ->
                val t = (step + 1f) / steps
                outgoing.volume = cos(t * PI / 2).toFloat()
                incoming.volume = sin(t * PI / 2).toFloat()
                delay(40)
            }
            outgoing.volume = 0f
            outgoing.pause()
            active = 1 - active
            index = nextIndex
            transitionJob = null
        }
    }

    fun release() {
        monitorJob?.cancel()
        transitionJob?.cancel()
        players.forEach { it.release() }
    }
}
