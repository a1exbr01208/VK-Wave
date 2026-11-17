package com.vkwave.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two-player equal-power crossfade engine.
 * The next item is prepared silently while the current item is playing.
 * Pass only media URIs that the signed-in user is authorized to play.
 */
class CrossfadeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val players = arrayOf(
        ExoPlayer.Builder(appContext).build(),
        ExoPlayer.Builder(appContext).build()
    )

    private var active = 0
    private var transitionJob: Job? = null
    private var monitorJob: Job? = null
    private var playlist: List<Uri> = emptyList()
    private var index = 0
    private var failedNextIndex: Int? = null
    private var released = false

    var crossfadeSeconds: Int = 7
        set(value) { field = value.coerceIn(2, 15) }

    fun setQueue(items: List<Uri>, startIndex: Int = 0) {
        if (released) return
        transitionJob?.cancel()
        transitionJob = null
        monitorJob?.cancel()
        failedNextIndex = null
        playlist = items.toList()
        index = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))

        players.forEachIndexed { playerIndex, player ->
            player.stop()
            player.clearMediaItems()
            player.volume = if (playerIndex == active) 1f else 0f
        }
        if (playlist.isEmpty()) return

        players[active].setMediaItem(MediaItem.fromUri(playlist[index]))
        players[active].prepare()
        prepareUpcoming()
        startMonitor()
    }

    fun play() {
        if (!released) players[active].play()
    }

    fun pause() {
        if (released) return
        players.forEach { it.pause() }
    }

    private fun prepareUpcoming() {
        if (released || playlist.size < 2) return
        val nextIndex = (index + 1) % playlist.size
        if (failedNextIndex == nextIndex) return

        val upcoming = players[1 - active]
        upcoming.stop()
        upcoming.clearMediaItems()
        upcoming.volume = 0f
        upcoming.setMediaItem(MediaItem.fromUri(playlist[nextIndex]))
        upcoming.prepare()
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && !released) {
                val outgoing = players[active]
                val duration = outgoing.duration
                val nextIndex = (index + 1) % playlist.size.coerceAtLeast(1)
                if (playlist.size > 1 && outgoing.isPlaying &&
                    duration > 0 && transitionJob == null &&
                    failedNextIndex != nextIndex
                ) {
                    val remaining = duration - outgoing.currentPosition
                    if (remaining in 0..(crossfadeSeconds * 1_000L)) {
                        beginCrossfade(
                            nextIndex,
                            CrossfadeMath.actualFadeDurationMs(remaining, crossfadeSeconds)
                        )
                    }
                }
                delay(100)
            }
        }
    }

    private fun beginCrossfade(nextIndex: Int, durationMs: Long) {
        if (transitionJob?.isActive == true || released) return

        val outgoing = players[active]
        val incomingIndex = 1 - active
        val incoming = players[incomingIndex]

        transitionJob = scope.launch {
            var completed = false
            try {
                // The incoming item was pre-buffered by prepareUpcoming().
                val ready = withTimeoutOrNull(20_000L) {
                    while (isActive &&
                        incoming.playbackState != Player.STATE_READY &&
                        incoming.playerError == null
                    ) {
                        delay(50)
                    }
                    incoming.playbackState == Player.STATE_READY &&
                        incoming.playerError == null
                } ?: false

                if (!ready) {
                    failedNextIndex = nextIndex
                    return@launch
                }

                incoming.volume = 0f
                incoming.play()
                val steps = (durationMs / 40L).coerceAtLeast(1L).toInt()
                repeat(steps) { step ->
                    if (!outgoing.isPlaying) {
                        incoming.pause()
                        while (isActive && !outgoing.isPlaying && !released) delay(50)
                        if (!isActive || released) return@launch
                        incoming.play()
                    }

                    val progress = (step + 1f) / steps
                    outgoing.volume = CrossfadeMath.outgoingGain(progress)
                    incoming.volume = CrossfadeMath.incomingGain(progress)
                    delay(40)
                }

                outgoing.volume = 0f
                outgoing.pause()
                incoming.volume = 1f
                active = incomingIndex
                index = nextIndex
                completed = true

                outgoing.stop()
                outgoing.clearMediaItems()
                failedNextIndex = null
                prepareUpcoming()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (!completed && !released) {
                    incoming.pause()
                    incoming.volume = 0f
                    outgoing.volume = 1f
                }
                transitionJob = null
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        monitorJob?.cancel()
        transitionJob?.cancel()
        players.forEach { it.release() }
        scope.cancel()
    }
}
