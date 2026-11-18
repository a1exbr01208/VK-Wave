package com.vkwave.app

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dual-player equal-power crossfade engine.
 *
 * Audio focus is managed by the owning playback service so the two ExoPlayers
 * do not compete for focus while overlapping. Only authorized MediaItems
 * supplied by a provider integration should be placed in the queue.
 */
class CrossfadeEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val players: Array<ExoPlayer>

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        players = Array(2) {
            ExoPlayer.Builder(appContext).build().apply {
                setAudioAttributes(attributes, false)
                setHandleAudioBecomingNoisy(true)
            }
        }
    }

    private var active = 0
    private var transitionJob: Job? = null
    private var monitorJob: Job? = null
    private var playlist: List<MediaItem> = emptyList()
    private var index = 0
    private val unavailableQueueIndexes = mutableSetOf<Int>()
    private var queueGeneration = 0L
    private var released = false
    private var outgoingGain = 1f
    private var incomingGain = 0f

    var onActivePlayerChanged: ((ExoPlayer) -> Unit)? = null
    var onQueueStateChanged: (() -> Unit)? = null

    var crossfadeSeconds: Int = 7
        set(value) { field = value.coerceIn(2, 15) }

    var repeatMode: QueueRepeatMode = QueueRepeatMode.OFF
        private set

    var masterVolume: Float = 1f
        private set

    val activePlayer: ExoPlayer
        get() = players[active]

    val deckPlayers: List<ExoPlayer>
        get() = players.toList()

    val queueItems: List<MediaItem>
        get() = playlist

    val currentQueueIndex: Int
        get() = index

    val isPlaying: Boolean
        get() = !released && activePlayer.isPlaying

    val canSkipNext: Boolean
        get() = nextPlayableQueueIndexes(
            playlist.size, index, repeatMode, unavailableQueueIndexes
        ).isNotEmpty()

    val canSkipPrevious: Boolean
        get() = previousPlayableQueueIndexes(
            playlist.size, index, repeatMode, unavailableQueueIndexes
        ).isNotEmpty()

    fun setQueue(items: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        if (released) return
        val wasPlaying = activePlayer.playWhenReady
        queueGeneration += 1
        transitionJob?.cancel()
        transitionJob = null
        monitorJob?.cancel()
        unavailableQueueIndexes.clear()
        playlist = items.toList()
        index = if (playlist.isEmpty()) 0 else startIndex.coerceIn(playlist.indices)

        players.forEachIndexed { playerIndex, player ->
            player.stop()
            player.clearMediaItems()
            player.volume = if (playerIndex == active) masterVolume else 0f
        }
        outgoingGain = 1f
        incomingGain = 0f

        if (playlist.isEmpty()) return

        activePlayer.setMediaItem(playlist[index], startPositionMs.coerceAtLeast(0L))
        activePlayer.prepare()
        applyRepeatMode(activePlayer)
        if (wasPlaying) activePlayer.play()
        prepareUpcoming()
        startMonitor()
    }

    fun addMediaItems(insertIndex: Int, items: List<MediaItem>) {
        if (released || items.isEmpty()) return
        val insertion = insertIndex.coerceIn(0..playlist.size)
        val updated = playlist.toMutableList().apply { addAll(insertion, items) }
        val newIndex = if (playlist.isEmpty()) 0
            else if (index >= insertion) index + items.size else index
        setQueue(updated, newIndex, activePlayer.currentPosition)
    }

    fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        if (released || fromIndex < 0 || toIndex > playlist.size ||
            fromIndex >= toIndex
        ) return
        val count = toIndex - fromIndex
        val updated = playlist.toMutableList()
        val moved = updated.subList(fromIndex, toIndex).toList()
        repeat(count) { updated.removeAt(fromIndex) }
        val destination = newIndex.coerceIn(0..updated.size)
        updated.addAll(destination, moved)

        val newCurrentIndex = if (index in fromIndex until toIndex) {
            destination + (index - fromIndex)
        } else {
            val afterRemoval = if (index >= toIndex) index - count else index
            if (afterRemoval >= destination) afterRemoval + count else afterRemoval
        }
        setQueue(updated, newCurrentIndex, activePlayer.currentPosition)
    }

    fun replaceMediaItems(fromIndex: Int, toIndex: Int, items: List<MediaItem>) {
        if (released || fromIndex < 0 || toIndex > playlist.size ||
            fromIndex > toIndex
        ) return
        val currentWasReplaced = index in fromIndex until toIndex
        val updated = playlist.toMutableList()
        repeat(toIndex - fromIndex) { updated.removeAt(fromIndex) }
        updated.addAll(fromIndex, items)

        val newIndex = when {
            updated.isEmpty() -> 0
            currentWasReplaced && items.isNotEmpty() ->
                fromIndex + (index - fromIndex).coerceAtMost(items.lastIndex)
            currentWasReplaced -> fromIndex.coerceAtMost(updated.lastIndex)
            index >= toIndex -> index + items.size - (toIndex - fromIndex)
            else -> index
        }
        val position = if (currentWasReplaced) 0L else activePlayer.currentPosition
        setQueue(updated, newIndex, position)
    }

    fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        replaceMediaItems(fromIndex, toIndex, emptyList())
    }

    fun prepare() {
        if (released || activePlayer.mediaItemCount == 0) return
        activePlayer.prepare()
        prepareUpcoming()
        startMonitor()
    }

    fun play() {
        if (released) return
        if (activePlayer.playbackState == Player.STATE_IDLE && activePlayer.mediaItemCount > 0) {
            prepare()
        }
        activePlayer.play()
    }

    fun pause() {
        if (released) return
        players.forEach { it.pause() }
    }

    fun stop() {
        if (released) return
        queueGeneration += 1
        transitionJob?.cancel()
        transitionJob = null
        monitorJob?.cancel()
        players.forEach { it.stop() }
        outgoingGain = 1f
        incomingGain = 0f
        applyVolumes()
    }

    fun seekTo(positionMs: Long) {
        if (!released && activePlayer.mediaItemCount > 0) {
            activePlayer.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        applyVolumes()
    }

    fun setRepeatMode(mode: QueueRepeatMode) {
        repeatMode = mode
        players.forEach(::applyRepeatMode)
        if (transitionJob?.isActive != true) prepareUpcoming()
    }

    fun skipToNext(): Boolean {
        if (released) return false
        val nextIndex = nextPlayableQueueIndexes(
            playlist.size, index, repeatMode, unavailableQueueIndexes
        ).firstOrNull() ?: return false
        return jumpTo(nextIndex)
    }

    fun skipToPrevious(): Boolean {
        if (released) return false
        val previousIndex = previousPlayableQueueIndexes(
            playlist.size, index, repeatMode, unavailableQueueIndexes
        ).firstOrNull() ?: return false
        return jumpTo(previousIndex)
    }

    fun seekToQueueItem(targetIndex: Int, positionMs: Long = 0L): Boolean =
        jumpTo(targetIndex, positionMs)

    private fun jumpTo(targetIndex: Int, positionMs: Long = 0L): Boolean {
        if (targetIndex !in playlist.indices) return false
        val wasPlaying = activePlayer.playWhenReady
        queueGeneration += 1
        transitionJob?.cancel()
        transitionJob = null
        monitorJob?.cancel()
        unavailableQueueIndexes.remove(targetIndex)

        val current = activePlayer
        val standby = players[1 - active]
        standby.stop()
        standby.clearMediaItems()
        current.stop()
        current.clearMediaItems()
        index = targetIndex
        outgoingGain = 1f
        incomingGain = 0f
        current.volume = masterVolume
        current.setMediaItem(playlist[index], positionMs.coerceAtLeast(0L))
        current.prepare()
        applyRepeatMode(current)
        if (wasPlaying) current.play()
        prepareUpcoming()
        startMonitor()
        return true
    }

    private fun applyRepeatMode(player: ExoPlayer) {
        player.repeatMode = if (repeatMode == QueueRepeatMode.ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    private fun nextIndexForAutomaticTransition(): Int? {
        if (repeatMode == QueueRepeatMode.ONE) return null
        return nextPlayableQueueIndexes(
            playlist.size, index, repeatMode, unavailableQueueIndexes
        ).firstOrNull()
    }

    private fun prepareUpcoming() {
        if (released) return
        val nextIndex = nextIndexForAutomaticTransition() ?: return

        val upcoming = players[1 - active]
        upcoming.stop()
        upcoming.clearMediaItems()
        upcoming.volume = 0f
        upcoming.setMediaItem(playlist[nextIndex])
        applyRepeatMode(upcoming)
        upcoming.prepare()
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && !released) {
                val outgoing = activePlayer
                val duration = outgoing.duration
                val nextIndex = nextIndexForAutomaticTransition()
                if (nextIndex != null && transitionJob == null) {
                    if (outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
                        startGaplessFallback(nextIndex)
                    } else if (outgoing.isPlaying && duration > 0) {
                        val remaining = duration - outgoing.currentPosition
                        if (remaining in 0..(crossfadeSeconds * 1_000L)) {
                            beginCrossfade(
                                nextIndex,
                                CrossfadeMath.actualFadeDurationMs(remaining, crossfadeSeconds)
                            )
                        }
                    }
                }
                delay(100)
            }
        }
    }

    private fun startGaplessFallback(nextIndex: Int) {
        if (transitionJob?.isActive == true || released) return
        val outgoing = activePlayer
        val incomingIndex = 1 - active
        val incoming = players[incomingIndex]
        val generation = queueGeneration
        val candidates = nextPlayableQueueIndexes(
            playlist.size, index, repeatMode, unavailableQueueIndexes
        ).dropWhile { it != nextIndex }
        if (candidates.isEmpty()) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (candidate in candidates) {
                    if (!isActive || released || generation != queueGeneration) return@launch
                    val item = playlist.getOrNull(candidate) ?: continue

                    incoming.stop()
                    incoming.clearMediaItems()
                    incoming.setMediaItem(item)
                    applyRepeatMode(incoming)
                    incoming.prepare()

                    val ready = withTimeoutOrNull(20_000L) {
                        while (isActive &&
                            incoming.playbackState != Player.STATE_READY &&
                            incoming.playbackState != Player.STATE_ENDED &&
                            incoming.playerError == null
                        ) {
                            delay(50)
                        }
                        incoming.playbackState == Player.STATE_READY &&
                            incoming.playerError == null
                    } ?: false

                    if (!ready) {
                        unavailableQueueIndexes.add(candidate)
                        onQueueStateChanged?.invoke()
                        incoming.stop()
                        incoming.clearMediaItems()
                        continue
                    }

                    incoming.volume = 0f
                    incoming.play()
                    outgoing.stop()
                    outgoing.clearMediaItems()

                    active = incomingIndex
                    index = candidate
                    outgoingGain = 1f
                    incomingGain = 0f
                    applyVolumes()
                    onActivePlayerChanged?.invoke(incoming)
                    prepareUpcoming()
                    return@launch
                }

                // Every remaining candidate failed. Stop the ended/error deck
                // and leave the queue intact so the user can manually retry one.
                outgoing.stop()
                incoming.stop()
                incoming.clearMediaItems()
                outgoingGain = 1f
                incomingGain = 0f
                applyVolumes()
            } finally {
                if (transitionJob === coroutineContext[Job]) {
                    transitionJob = null
                }
            }
        }
        transitionJob = job
        job.start()
    }

    private fun beginCrossfade(nextIndex: Int, durationMs: Long) {
        if (transitionJob?.isActive == true || released) return

        val outgoing = activePlayer
        val incomingIndex = 1 - active
        val incoming = players[incomingIndex]
        val generation = queueGeneration
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            try {
                val ready = withTimeoutOrNull(20_000L) {
                    while (isActive &&
                        incoming.playbackState != Player.STATE_READY &&
                        incoming.playbackState != Player.STATE_ENDED &&
                        incoming.playerError == null
                    ) {
                        delay(50)
                    }
                    incoming.playbackState == Player.STATE_READY &&
                        incoming.playerError == null
                } ?: false

                if (!ready) {
                    unavailableQueueIndexes.add(nextIndex)
                    onQueueStateChanged?.invoke()
                    incoming.stop()
                    incoming.clearMediaItems()
                    prepareUpcoming()
                    return@launch
                }

                incoming.volume = 0f
                incoming.play()
                val steps = (durationMs / 40L).coerceAtLeast(1L).toInt()
                for (step in 0 until steps) {
                    if (incoming.playerError != null || incoming.playbackState == Player.STATE_IDLE) {
                        unavailableQueueIndexes.add(nextIndex)
                        onQueueStateChanged?.invoke()
                        incoming.stop()
                        incoming.clearMediaItems()
                        outgoingGain = 1f
                        incomingGain = 0f
                        applyVolumes()
                        prepareUpcoming()
                        return@launch
                    }

                    if (!outgoing.isPlaying) {
                        if (outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
                            outgoingGain = 0f
                            incomingGain = 1f
                            applyVolumes()
                            break
                        }
                        incoming.pause()
                        while (isActive && !outgoing.isPlaying &&
                            outgoing.playbackState != Player.STATE_ENDED && outgoing.playerError == null && !released
                        ) {
                            delay(50)
                        }
                        if (!isActive || released) return@launch
                        if (outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
                            outgoingGain = 0f
                            incomingGain = 1f
                            applyVolumes()
                            break
                        }
                        incoming.play()
                    }

                    val progress = (step + 1f) / steps
                    outgoingGain = CrossfadeMath.outgoingGain(progress)
                    incomingGain = CrossfadeMath.incomingGain(progress)
                    applyVolumes()
                    delay(40)
                }

                outgoing.pause()
                active = incomingIndex
                index = nextIndex
                outgoingGain = 1f
                incomingGain = 0f
                completed = true
                applyRepeatMode(incoming)
                applyVolumes()
                outgoing.stop()
                outgoing.clearMediaItems()
                onActivePlayerChanged?.invoke(incoming)
                prepareUpcoming()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (!completed && !released && generation == queueGeneration) {
                    incoming.pause()
                    outgoingGain = 1f
                    incomingGain = 0f
                    applyVolumes()
                }
                if (transitionJob === coroutineContext[Job]) {
                    transitionJob = null
                }
            }
        }
        transitionJob = job
        job.start()
    }

    private fun applyVolumes() {
        players[active].volume = outgoingGain * masterVolume
        players[1 - active].volume = incomingGain * masterVolume
    }

    fun release() {
        if (released) return
        released = true
        queueGeneration += 1
        monitorJob?.cancel()
        transitionJob?.cancel()
        players.forEach { it.release() }
        scope.cancel()
    }
}
