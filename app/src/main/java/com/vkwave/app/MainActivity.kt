package com.vkwave.app

import android.os.Bundle
import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Future
import java.util.concurrent.Executor
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101116)
private val Panel = Color(0xFF1B1D25)
private val Accent = Color(0xFF9B7BFF)
private val Muted = Color(0xFF9699A8)

private data class WaveTab(val title: String, val icon: ImageVector)
private val waveTabs = listOf(
    WaveTab("Главная", Icons.Default.Home),
    WaveTab("Поиск", Icons.Default.Search),
    WaveTab("Медиатека", Icons.Default.LibraryMusic),
    WaveTab("Настройки", Icons.Default.Settings)
)

class MainActivity : ComponentActivity() {
    private val mediaControllerState = mutableStateOf<MediaController?>(null)
    private val crossfadeState = mutableFloatStateOf(7f)
    private var controllerFuture: Future<MediaController>? = null
    private var saveCrossfadeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            applicationContext.savedCrossfadeSeconds.collect { saved ->
                crossfadeState.floatValue = saved.toFloat()
            }
        }
        setContent {
            VKWaveApp(
                mediaControllerState.value,
                crossfadeState.floatValue,
                ::updateCrossfade
            )
        }
    }

    private fun updateCrossfade(seconds: Float) {
        crossfadeState.floatValue = seconds
        saveCrossfadeJob?.cancel()
        saveCrossfadeJob = lifecycleScope.launch {
            delay(350)
            applicationContext.persistCrossfadeSeconds(seconds.toInt())
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, WavePlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture === future) {
                try {
                    mediaControllerState.value = future.get()
                } catch (_: Exception) {
                    mediaControllerState.value = null
                }
            }
        }, Executor { command -> runOnUiThread(command) })
    }

    override fun onStop() {
        mediaControllerState.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}

@Composable
fun VKWaveApp(
    controller: MediaController?,
    crossfade: Float,
    onCrossfadeChange: (Float) -> Unit
) {
    var tab by remember { mutableStateOf("Главная") }
    var playing by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var hasMedia by remember(controller) { mutableStateOf((controller?.mediaItemCount ?: 0) > 0) }
    var canSkipPrevious by remember(controller) {
        mutableStateOf(controller?.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) == true)
    }
    var canSkipNext by remember(controller) {
        mutableStateOf(controller?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) == true)
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                hasMedia = (controller?.mediaItemCount ?: 0) > 0
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                hasMedia = mediaItem != null
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                canSkipPrevious = availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                canSkipNext = availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    val currentTitle = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
        ?.takeIf { it.isNotBlank() } ?: "VK Wave готов к воспроизведению"
    val currentArtist = controller?.currentMediaItem?.mediaMetadata?.artist?.toString()
        ?.takeIf { it.isNotBlank() } ?: "Источник аудио пока не подключён"

    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
            if (maxWidth < 600.dp) {
                Column(Modifier.fillMaxSize()) {
                    WaveHeader(tab, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                    PlayerCard(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                        currentTitle to currentArtist, playing, hasMedia, canSkipPrevious, canSkipNext,
                        { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                        { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem() } },
                        { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() } }
                    )
                    CrossfadeCard(crossfade, onCrossfadeChange, Modifier.padding(16.dp))
                    NavigationBar(containerColor = Panel, contentColor = Color.White) {
                        waveTabs.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.title,
                                onClick = { tab = item.title },
                                icon = { Icon(item.icon, contentDescription = item.title) },
                                label = { Text(item.title, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent, selectedTextColor = Accent,
                                    indicatorColor = Accent.copy(alpha = .16f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(84.dp).fillMaxHeight().padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        WaveLogo()
                        waveTabs.forEach { item ->
                            IconButton(onClick = { tab = item.title }) {
                                Icon(item.icon, contentDescription = item.title,
                                    tint = if (tab == item.title) Accent else Muted)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.AccountCircle, contentDescription = "Профиль",
                            tint = Muted, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.fillMaxSize().padding(end = 24.dp, top = 24.dp, bottom = 20.dp)) {
                        WaveHeader(tab)
                        Spacer(Modifier.height(20.dp))
                        PlayerCard(
                            Modifier.weight(1f).fillMaxWidth(), currentTitle to currentArtist, playing, hasMedia,
                            canSkipPrevious, canSkipNext,
                            { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                            { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem() } },
                            { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() } }
                        )
                        CrossfadeCard(crossfade, onCrossfadeChange, Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveLogo() {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(listOf(Accent, Color(0xFF43C6AC)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.GraphicEq, contentDescription = "VK Wave", tint = Color.White)
    }
}

@Composable
private fun WaveHeader(tab: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("VK WAVE", color = Color.White, fontSize = 12.sp,
                letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Text(tab, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Surface(color = Panel, shape = CircleShape) {
            Row(Modifier.clickable { }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, contentDescription = null,
                    tint = Accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("VK не подключён", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlayerCard(
    modifier: Modifier,
    track: Pair<String, String>,
    playing: Boolean,
    hasMedia: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Box(
        modifier.clip(RoundedCornerShape(26.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF35305B), Color(0xFF1C2738), Color(0xFF171922)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(164.dp).clip(RoundedCornerShape(22.dp)).background(
                    Brush.linearGradient(listOf(Color(0xFFB58CFF), Color(0xFF436B9C), Color(0xFF20243C)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = "Обложка демо-трека",
                    tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(76.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(track.first, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(track.second, color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = canSkipPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий трек", tint = if (canSkipPrevious) Color.White else Muted)
                }
                FilledIconButton(onClick = onPlay, enabled = hasMedia,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
                    modifier = Modifier.size(58.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Пауза демо" else "Запуск демо",
                        modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onNext, enabled = canSkipNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Следующий трек", tint = if (canSkipNext) Color.White else Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (hasMedia) "УПРАВЛЕНИЕ ЧЕРЕЗ ANDROID MEDIA SESSION" else "НЕТ ДОСТУПНОГО ИСТОЧНИКА АУДИО",
                color = Color.White.copy(alpha = .6f), fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun CrossfadeCard(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Плавный переход", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Наложение двух треков", color = Muted, fontSize = 12.sp)
                }
                Text(value.toInt().toString() + " сек.", color = Accent, fontWeight = FontWeight.Bold)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = 2f..15f, steps = 12)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("2 сек.", color = Muted, fontSize = 11.sp)
                Text("15 сек.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}
