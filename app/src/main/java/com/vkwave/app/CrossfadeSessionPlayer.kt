package com.vkwave.app

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingSimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Exposes the active deck through MediaSession while routing playback commands
 * to the dual-player engine. The session follows the active ExoPlayer as a
 * crossfade completes, so metadata and position remain system-visible.
 */
@OptIn(UnstableApi::class)
@UnstableApi
class CrossfadeSessionPlayer(
    initialPlayer: Player,
    private val engine: CrossfadeEngine,
    private val requestAudioFocus: () -> Boolean,
    private val abandonAudioFocus: () -> Unit
) : ForwardingSimpleBasePlayer(initialPlayer) {

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .remove(Player.COMMAND_SET_SHUFFLE_MODE)
            .remove(Player.COMMAND_SET_AUDIO_ATTRIBUTES)

        if (engine.canSkipNext) {
            commands.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            commands.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }

        if (engine.canSkipPrevious) {
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        } else {
            commands.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }

        return state.buildUpon()
            .setAvailableCommands(commands.build())
            .setVolume(engine.masterVolume)
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        engine.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (requestAudioFocus()) engine.play()
        } else {
            engine.pause()
            abandonAudioFocus()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        engine.setQueue(mediaItems, startIndex, startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>
    ): ListenableFuture<*> {
        engine.addMediaItems(index, mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int
    ): ListenableFuture<*> {
        engine.moveMediaItems(fromIndex, toIndex, newIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    ): ListenableFuture<*> {
        engine.replaceMediaItems(fromIndex, toIndex, mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int
    ): ListenableFuture<*> {
        engine.removeMediaItems(fromIndex, toIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.skipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.skipToPrevious()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> engine.seekTo(positionMs)
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        engine.setRepeatMode(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> QueueRepeatMode.ONE
                Player.REPEAT_MODE_ALL -> QueueRepeatMode.ALL
                else -> QueueRepeatMode.OFF
            }
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        engine.setMasterVolume(volume)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        abandonAudioFocus()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // The service owns the engine and releases it in onDestroy().
        return Futures.immediateVoidFuture()
    }
}
