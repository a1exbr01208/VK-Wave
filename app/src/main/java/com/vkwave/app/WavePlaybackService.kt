package com.vkwave.app

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes as PlatformAudioAttributes
import androidx.media3.common.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns both playback decks and the Android media session outside the activity
 * lifecycle. Audio focus is centralized so overlapping ExoPlayers never
 * compete with each other for focus during a crossfade.
 */
@OptIn(UnstableApi::class)
class WavePlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var crossfadeEngine: CrossfadeEngine? = null
    private var sessionPlayer: CrossfadeSessionPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val engine = crossfadeEngine ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.setMasterVolume(1f)
                if (resumeOnFocusGain) engine.play()
                resumeOnFocusGain = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = engine.isPlaying
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                engine.setMasterVolume(0.2f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                engine.pause()
                abandonAudioFocus()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val engine = CrossfadeEngine(this)
        crossfadeEngine = engine

        val player = CrossfadeSessionPlayer(
            initialPlayer = engine.activePlayer,
            engine = engine,
            requestAudioFocus = ::requestAudioFocus,
            abandonAudioFocus = ::abandonAudioFocus
        )
        sessionPlayer = player
        engine.onActivePlayerChanged = player::switchTo

        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { audioFocusRequest = it }

        return when (manager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> true
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                resumeOnFocusGain = true
                false
            }
            else -> false
        }
    }

    private fun abandonAudioFocus() {
        resumeOnFocusGain = false
        val request = audioFocusRequest ?: return
        audioManager?.abandonAudioFocusRequest(request)
        audioFocusRequest = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null
        crossfadeEngine?.release()
        crossfadeEngine = null
        audioManager = null
        super.onDestroy()
    }
}
