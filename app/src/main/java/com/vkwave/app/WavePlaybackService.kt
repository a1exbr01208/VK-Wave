package com.vkwave.app

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes as PlatformAudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns both playback decks and the Android media session outside the activity
 * lifecycle. Audio focus is centralized so overlapping ExoPlayers never
 * compete with each other for focus during a crossfade.
 */
@UnstableApi
class WavePlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var crossfadeEngine: CrossfadeEngine? = null
    private var sessionPlayers: List<CrossfadeSessionPlayer> = emptyList()
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        serviceScope.launch {
            applicationContext.savedCrossfadeSeconds.collect { seconds ->
                engine.crossfadeSeconds = seconds
            }
        }

        val decks = engine.deckPlayers
        val adapters = decks.map { deck ->
            CrossfadeSessionPlayer(
                initialPlayer = deck,
                engine = engine,
                requestAudioFocus = ::requestAudioFocus,
                abandonAudioFocus = ::abandonAudioFocus
            )
        }
        sessionPlayers = adapters

        mediaSession = MediaSession.Builder(this, adapters.first()).build()
        engine.onQueueStateChanged = {
            adapters.forEach { it.refreshEngineState() }
        }
        engine.onActivePlayerChanged = { activeDeck ->
            val activeIndex = decks.indexOfFirst { it === activeDeck }
            if (activeIndex >= 0) {
                mediaSession?.setPlayer(adapters[activeIndex])
            }
        }
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
        sessionPlayers = emptyList()
        crossfadeEngine?.release()
        crossfadeEngine = null
        serviceScope.cancel()
        audioManager = null
        super.onDestroy()
    }
}
