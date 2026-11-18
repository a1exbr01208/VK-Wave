package com.vkwave.app

import android.os.Bundle
import android.Manifest
import android.os.Build
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
import kotlinx.coroutines.isActive
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    private val musicSources by lazy { createMusicSourceRegistry(applicationContext) }
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
                ::updateCrossfade,
                ::startDemoPlayback,
                musicSources,
                ::playSourceTracks
            )
        }
    }

    private fun startDemoPlayback() {
        val controller = mediaControllerState.value ?: return
        lifecycleScope.launch {
            val items = DemoAudioProvider(applicationContext).createPlaylist()
            if (mediaControllerState.value !== controller) return@launch
            controller.setMediaItems(items, 0, 0L)
            controller.prepare()
            controller.play()
        }
    }

    private fun playSourceTracks(tracks: List<WaveTrack>, startIndex: Int) {
        val controller = mediaControllerState.value ?: return
        lifecycleScope.launch {
            val items = tracks.mapNotNull { track ->
                val source = musicSources.get(track.sourceId) ?: return@mapNotNull null
                runCatching { track to source.resolvePlayback(track) }.getOrNull()
            }.map { (track, uri) -> track.toMediaItem(uri) }
            if (items.isNotEmpty() && mediaControllerState.value === controller) {
                controller.setMediaItems(items, startIndex.coerceIn(items.indices), 0L)
                controller.prepare()
                controller.play()
            }
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
    onCrossfadeChange: (Float) -> Unit,
    onDemoPlayback: () -> Unit,
    sourceRegistry: MusicSourceRegistry,
    onPlaySourceTracks: (List<WaveTrack>, Int) -> Unit
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
    var positionMs by remember(controller) { mutableLongStateOf(0L) }
    var durationMs by remember(controller) { mutableLongStateOf(0L) }

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

    LaunchedEffect(controller, hasMedia, playing) {
        if (controller == null || !hasMedia) {
            positionMs = 0L
            durationMs = 0L
        } else {
            while (isActive) {
                positionMs = controller.currentPosition.coerceAtLeast(0L)
                durationMs = controller.duration.takeIf { it > 0L } ?: 0L
                delay(500)
            }
        }
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
                    when (tab) {
                        "Поиск" -> SourceSearchScreen(
                            sourceRegistry,
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                            onPlaySourceTracks
                        )
                        "Медиатека" -> SourceLibraryScreen(
                            sourceRegistry,
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                            onPlaySourceTracks
                        )
                        else -> PlayerCard(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                            currentTitle to currentArtist, playing, hasMedia, canSkipPrevious, canSkipNext,
                            positionMs, durationMs, controller != null,
                            { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                            { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem() } },
                            { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() } },
                            { target -> controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) it.seekTo(target) } },
                            onDemoPlayback
                        )
                    }
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
                        when (tab) {
                            "Поиск" -> SourceSearchScreen(sourceRegistry, Modifier.weight(1f).fillMaxWidth(), onPlaySourceTracks)
                            "Медиатека" -> SourceLibraryScreen(sourceRegistry, Modifier.weight(1f).fillMaxWidth(), onPlaySourceTracks)
                            else -> PlayerCard(
                                Modifier.weight(1f).fillMaxWidth(), currentTitle to currentArtist, playing, hasMedia,
                                canSkipPrevious, canSkipNext, positionMs, durationMs, controller != null,
                                { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                                { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem() } },
                                { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() } },
                                { target -> controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) it.seekTo(target) } },
                                onDemoPlayback
                            )
                        }
                        CrossfadeCard(crossfade, onCrossfadeChange, Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLibraryScreen(
    sourceRegistry: MusicSourceRegistry,
    modifier: Modifier = Modifier,
    onPlayTracks: (List<WaveTrack>, Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val source = remember(sourceRegistry) { sourceRegistry.get(LocalAudioSource.SOURCE_ID) }
    var tracks by remember { mutableStateOf(emptyList<WaveTrack>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Музыка, сохранённая на этом устройстве") }
    val scope = rememberCoroutineScope()
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun loadLibrary() {
        val provider = source ?: return
        scope.launch {
            loading = true
            try {
                tracks = provider.library().tracks
                message = if (tracks.isEmpty()) "На устройстве пока не найдено музыкальных файлов" else "Найдено файлов: \${tracks.size}"
            } catch (error: Exception) {
                message = error.message ?: "Не удалось прочитать медиатеку"
            } finally {
                loading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadLibrary() else message = "Без разрешения Android не покажет локальную музыку"
    }

    LaunchedEffect(source) {
        if (source?.connectionState() == SourceConnectionState.Available) loadLibrary()
        else message = "Нужно разрешить доступ к аудиофайлам"
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Медиатека устройства", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Muted, fontSize = 12.sp)
        Button(
            onClick = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) loadLibrary() else permissionLauncher.launch(permission)
            },
            enabled = !loading
        ) {
            Text(if (loading) "Загрузка…" else "Разрешить доступ / обновить")
        }
        tracks.forEachIndexed { index, track ->
            Surface(
                color = Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPlayTracks(tracks, index) }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(track.artist, color = Muted, fontSize = 12.sp)
                    Text("▶  Слушать", color = Accent, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SourceSearchScreen(
    sourceRegistry: MusicSourceRegistry,
    modifier: Modifier = Modifier,
    onPlayTracks: (List<WaveTrack>, Int) -> Unit
) {
    val source = remember(sourceRegistry) { sourceRegistry.get(AudiusMusicSource.SOURCE_ID) }
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(emptyList<WaveTrack>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Ищи независимых исполнителей и треки в каталоге Audius") }
    val scope = rememberCoroutineScope()

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Поиск музыки", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Источник: Audius · открытый музыкальный каталог", color = Muted, fontSize = 12.sp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Трек или исполнитель") },
            singleLine = true
        )
        Button(
            onClick = {
                val provider = source
                if (provider != null && query.isNotBlank()) {
                    scope.launch {
                        loading = true
                        message = "Ищем треки…"
                        try {
                            tracks = provider.search(query).tracks
                            message = if (tracks.isEmpty()) "Ничего не найдено" else "Найдено треков: ${tracks.size}"
                        } catch (error: Exception) {
                            message = error.message ?: "Не удалось связаться с Audius"
                            tracks = emptyList()
                        } finally {
                            loading = false
                        }
                    }
                }
            },
            enabled = !loading && query.isNotBlank() && source != null
        ) {
            Text(if (loading) "Поиск…" else "Найти")
        }
        Text(message, color = Muted, fontSize = 12.sp)
        tracks.forEachIndexed { index, track ->
            Surface(
                color = Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPlayTracks(tracks, index) }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(track.artist, color = Muted, fontSize = 12.sp)
                    Text("▶  Слушать", color = Accent, fontSize = 11.sp)
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
    positionMs: Long,
    durationMs: Long,
    canRunDemo: Boolean,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onDemoPlayback: () -> Unit
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(positionMs.toFloat()) }
    LaunchedEffect(positionMs, isScrubbing) {
        if (!isScrubbing) seekValue = positionMs.toFloat()
    }
    val seekMaximum = durationMs.toFloat().coerceAtLeast(1f)
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
            Spacer(Modifier.height(12.dp))
            Slider(
                value = seekValue.coerceIn(0f, seekMaximum),
                onValueChange = {
                    isScrubbing = true
                    seekValue = it
                },
                onValueChangeFinished = {
                    onSeek(seekValue.toLong())
                    isScrubbing = false
                },
                valueRange = 0f..seekMaximum,
                enabled = hasMedia && durationMs > 0L,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPlaybackTime(if (isScrubbing) seekValue.toLong() else positionMs), color = Muted, fontSize = 10.sp)
                Text(formatPlaybackTime(durationMs), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(12.dp))
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
            TextButton(onClick = onDemoPlayback, enabled = canRunDemo) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Тест движка · 2 синтезированных тона", fontSize = 11.sp)
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
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
