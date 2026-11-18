package com.vkwave.app

import android.os.Bundle
import android.Manifest
import android.os.Build
import android.widget.Toast
import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Future
import java.util.concurrent.Executor
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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

private data class WavePalette(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val muted: Color,
    val foreground: Color
)

private val LocalWavePalette = compositionLocalOf {
    WavePalette(
        Color(0xFF101116), Color(0xFF1B1D25),
        Color(0xFF9B7BFF), Color(0xFF9699A8), Color.White
    )
}

private val Ink: Color @Composable get() = LocalWavePalette.current.background
private val Panel: Color @Composable get() = LocalWavePalette.current.surface
private val Accent: Color @Composable get() = LocalWavePalette.current.accent
private val Muted: Color @Composable get() = LocalWavePalette.current.muted

private fun paletteFor(theme: String): WavePalette = when (theme) {
    "light" -> WavePalette(
        Color(0xFFF4F4F7), Color.White, Color(0xFF7654D8),
        Color(0xFF777783), Color(0xFF191920)
    )
    "glass" -> WavePalette(
        Color(0xFF100F1B), Color(0xFF29243A), Color(0xFFC09BFF),
        Color(0xFFB9AECF), Color(0xFFF9F6FF)
    )
    else -> WavePalette(
        Color(0xFF101116), Color(0xFF1B1D25), Color(0xFF9B7BFF),
        Color(0xFF9699A8), Color.White
    )
}

private data class WaveTab(val title: String, val icon: ImageVector)
private data class SourceSearchGroup(
    val source: SourceDescriptor,
    val tracks: List<WaveTrack> = emptyList(),
    val message: String? = null,
    val nextPageToken: String? = null,
    val loadingMore: Boolean = false
)
private val waveTabs = listOf(
    WaveTab("Главная", Icons.Default.Home),
    WaveTab("Поиск", Icons.Default.Search),
    WaveTab("Медиатека", Icons.Default.LibraryMusic),
    WaveTab("Очередь", Icons.AutoMirrored.Filled.QueueMusic),
    WaveTab("Профиль", Icons.Default.Person)
)

class MainActivity : ComponentActivity() {
    private val mediaControllerState = mutableStateOf<MediaController?>(null)
    private val crossfadeState = mutableFloatStateOf(7f)
    private val themeState = mutableStateOf("dark")
    private val musicSources by lazy { createMusicSourceRegistry(applicationContext) }
    private var controllerFuture: Future<MediaController>? = null
    private var saveCrossfadeJob: Job? = null
    private var resolvePlaybackJob: Job? = null
    private var playbackRequestGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            applicationContext.savedCrossfadeSeconds.collect { saved ->
                crossfadeState.floatValue = saved.toFloat()
            }
        }
        lifecycleScope.launch {
            applicationContext.savedWaveTheme.collect { saved -> themeState.value = saved }
        }
        lifecycleScope.launch {
            val jamendo = musicSources.get(JamendoMusicSource.SOURCE_ID) as? JamendoMusicSource
            applicationContext.savedJamendoClientId.collect { saved ->
                if (saved != null) jamendo?.setClientId(saved)
            }
        }
        setContent {
            VKWaveApp(
                mediaControllerState.value,
                crossfadeState.floatValue,
                ::updateCrossfade,
                ::startDemoPlayback,
                musicSources,
                ::playSourceTracks,
                themeState.value,
                ::updateTheme
            )
        }
    }

    private fun startDemoPlayback() {
        val controller = mediaControllerState.value ?: return
        val generation = ++playbackRequestGeneration
        resolvePlaybackJob?.cancel()
        resolvePlaybackJob = lifecycleScope.launch {
            try {
                val items = DemoAudioProvider(applicationContext).createPlaylist()
                if (generation != playbackRequestGeneration ||
                    mediaControllerState.value !== controller
                ) return@launch
                controller.setMediaItems(items, 0, 0L)
                controller.prepare()
                controller.play()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == playbackRequestGeneration) {
                    Toast.makeText(
                        applicationContext,
                        "Не удалось создать тестовые аудиофайлы",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (generation == playbackRequestGeneration) resolvePlaybackJob = null
            }
        }
    }

    private fun playSourceTracks(tracks: List<WaveTrack>, startIndex: Int) {
        val controller = mediaControllerState.value ?: return
        val generation = ++playbackRequestGeneration
        resolvePlaybackJob?.cancel()
        resolvePlaybackJob = lifecycleScope.launch {
            try {
                val resolved = tracks.mapIndexedNotNull { originalIndex, track ->
                    val source = musicSources.get(track.sourceId) ?: return@mapIndexedNotNull null
                    val uri = try {
                        source.resolvePlayback(track)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } ?: return@mapIndexedNotNull null
                    originalIndex to track.toMediaItem(uri)
                }

                if (generation != playbackRequestGeneration ||
                    mediaControllerState.value !== controller
                ) return@launch

                if (resolved.isEmpty()) {
                    Toast.makeText(
                        applicationContext,
                        "Не удалось получить аудиопоток ни для одного трека",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                if (resolved.size < tracks.size) {
                    val skipped = tracks.size - resolved.size
                    Toast.makeText(
                        applicationContext,
                        "Пропущено треков без доступного потока: $skipped",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // If the requested track could not be resolved, start at the next
                // playable result or the nearest earlier playable result.
                val resolvedStart = resolveQueueStartIndex(
                    resolved.map { it.first },
                    startIndex
                ) ?: return@launch
                if (resolved[resolvedStart].first != startIndex) {
                    Toast.makeText(
                        applicationContext,
                        "Выбранный трек недоступен, запускаю ближайший доступный",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                controller.setMediaItems(resolved.map { it.second }, resolvedStart, 0L)
                controller.prepare()
                controller.play()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == playbackRequestGeneration) {
                    Toast.makeText(
                        applicationContext,
                        "Не удалось подготовить выбранную музыку",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (generation == playbackRequestGeneration) resolvePlaybackJob = null
            }
        }
    }

    private fun updateTheme(theme: String) {
        val normalized = theme.takeIf { it in setOf("dark", "light", "glass") } ?: "dark"
        themeState.value = normalized
        lifecycleScope.launch { applicationContext.persistWaveTheme(normalized) }
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
        playbackRequestGeneration += 1
        resolvePlaybackJob?.cancel()
        resolvePlaybackJob = null
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
    onPlaySourceTracks: (List<WaveTrack>, Int) -> Unit,
    selectedTheme: String,
    onThemeChange: (String) -> Unit
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
    val crossfadeProgress by WaveCrossfadeStatus.progress.collectAsState()

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
        ?.takeIf { it.isNotBlank() } ?: "Wave Player готов к воспроизведению"
    val currentArtist = controller?.currentMediaItem?.mediaMetadata?.artist?.toString()
        ?.takeIf { it.isNotBlank() } ?: "Источник аудио пока не подключён"

    val palette = paletteFor(selectedTheme)
    CompositionLocalProvider(LocalWavePalette provides palette) {
    MaterialTheme(colorScheme = if (selectedTheme == "light") lightColorScheme(
        background = palette.background, surface = palette.surface, primary = palette.accent,
        onBackground = palette.foreground, onSurface = palette.foreground
    ) else darkColorScheme(
        background = palette.background, surface = palette.surface, primary = palette.accent,
        onBackground = palette.foreground, onSurface = palette.foreground
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
                        "Очередь" -> QueueScreen(controller, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
                        "Профиль" -> SettingsScreen(sourceRegistry, selectedTheme, onThemeChange, crossfade, onCrossfadeChange, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
                        "Плеер" -> PlayerCard(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                            currentTitle to currentArtist, playing, hasMedia, canSkipPrevious, canSkipNext,
                            positionMs, durationMs, crossfadeProgress, controller != null,
                            { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                            { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem() } },
                            { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() } },
                            { target -> controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) it.seekTo(target) } },
                            onDemoPlayback
                        )
                        else -> HomeScreen(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                            currentTitle, currentArtist, hasMedia, playing,
                            { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                            { tab = "Плеер" },
                            { tab = "Поиск" },
                            { tab = "Медиатека" }
                        )
                    }
                    if (hasMedia && tab != "Плеер") {
                        MiniPlayer(
                            currentTitle, currentArtist, playing,
                            onClick = { tab = "Плеер" },
                            onPlay = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    NavigationBar(containerColor = Panel, contentColor = LocalWavePalette.current.foreground) {
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
                            "Очередь" -> QueueScreen(controller, Modifier.weight(1f).fillMaxWidth())
                            "Профиль" -> SettingsScreen(sourceRegistry, selectedTheme, onThemeChange, crossfade, onCrossfadeChange, Modifier.weight(1f).fillMaxWidth())
                            "Плеер" -> PlayerCard(
                                Modifier.weight(1f).fillMaxWidth(), currentTitle to currentArtist, playing, hasMedia,
                                canSkipPrevious, canSkipNext, positionMs, durationMs, crossfadeProgress, controller != null,
                                { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                                { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem() } },
                                { controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() } },
                                { target -> controller?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) it.seekTo(target) } },
                                onDemoPlayback
                            )
                            else -> HomeScreen(
                                Modifier.weight(1f).fillMaxWidth(),
                                currentTitle, currentArtist, hasMedia, playing,
                                { controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() } },
                                { tab = "Плеер" }, { tab = "Поиск" }, { tab = "Медиатека" }
                            )
                        }
                        if (hasMedia && tab != "Плеер") {
                            MiniPlayer(currentTitle, currentArtist, playing,
                                onClick = { tab = "Плеер" },
                                onPlay = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                                modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
    }
    }
}

private suspend fun loadSourceStatusLabels(registry: MusicSourceRegistry): Map<String, String> {
    val labels = linkedMapOf<String, String>()
    for (descriptor in registry.all()) {
        val state = try {
            registry.get(descriptor.id)?.connectionState()
                ?: SourceConnectionState.Unavailable("Источник отключён")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SourceConnectionState.Unavailable(error.message ?: "Не удалось проверить источник")
        }
        labels[descriptor.id] = when (state) {
            SourceConnectionState.Available -> "Готов к работе"
            SourceConnectionState.NeedsPermission -> "Ожидает разрешения Android"
            SourceConnectionState.NeedsAuthentication -> "Требуется авторизация"
            is SourceConnectionState.NeedsConfiguration -> state.reason
            is SourceConnectionState.Unavailable -> state.reason
        }
    }
    return labels
}

@Composable
private fun SettingsScreen(
    sourceRegistry: MusicSourceRegistry,
    selectedTheme: String,
    onThemeChange: (String) -> Unit,
    crossfade: Float,
    onCrossfadeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var statuses by remember { mutableStateOf(emptyMap<String, String>()) }
    var refreshing by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val jamendo = remember(sourceRegistry) {
        sourceRegistry.get(JamendoMusicSource.SOURCE_ID) as? JamendoMusicSource
    }
    var jamendoClientId by remember(jamendo) {
        mutableStateOf(jamendo?.configuredClientId.orEmpty())
    }
    var savingJamendo by remember { mutableStateOf(false) }
    var jamendoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(jamendo) {
        context.savedJamendoClientId.collect { saved ->
            jamendoClientId = saved ?: jamendo?.configuredClientId.orEmpty()
        }
    }

    suspend fun refreshStatuses() {
        refreshing = true
        try {
            statuses = loadSourceStatusLabels(sourceRegistry)
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(sourceRegistry) { refreshStatuses() }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                WaveLogo()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("WAVE PLAYER", color = LocalWavePalette.current.foreground,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Профиль и настройки", color = Muted, fontSize = 12.sp)
                    Text("Музыка без лишнего шума", color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Default.Equalizer, contentDescription = null, tint = Accent,
                    modifier = Modifier.size(28.dp))
            }
        }
        Text("Оформление приложения", color = LocalWavePalette.current.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Тема", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
                listOf("dark" to "Тёмная", "light" to "Светлая", "glass" to "Glass").forEach { (id, title) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selectedTheme == id) Accent.copy(alpha = .16f) else Color.Transparent)
                            .clickable { onThemeChange(id) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(
                            when (id) {
                                "light" -> Color(0xFFF4F4F7)
                                "glass" -> Color(0xFF5C438F)
                                else -> Color(0xFF171922)
                            }
                        ))
                        Spacer(Modifier.width(12.dp))
                        Text(title, color = LocalWavePalette.current.foreground, modifier = Modifier.weight(1f))
                        RadioButton(selected = selectedTheme == id, onClick = { onThemeChange(id) })
                    }
                }
                Text("Выбор сохраняется автоматически", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Воспроизведение", color = LocalWavePalette.current.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        CrossfadeCard(crossfade, onCrossfadeChange)
        Text("Подключённые музыкальные источники", color = LocalWavePalette.current.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jamendo", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
                Text(
                    "Необязательный публичный Client ID из Jamendo Developer Portal. Он нужен только для поиска и потока Jamendo.",
                    color = Muted,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = jamendoClientId,
                    onValueChange = { jamendoClientId = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Jamendo Client ID") }
                )
                Button(
                    onClick = {
                        scope.launch {
                            savingJamendo = true
                            jamendoMessage = null
                            try {
                                jamendo?.setClientId(jamendoClientId)
                                context.persistJamendoClientId(jamendoClientId)
                                statuses = loadSourceStatusLabels(sourceRegistry)
                                jamendoMessage = "Настройки Jamendo сохранены"
                            } catch (error: Exception) {
                                jamendoMessage = error.message ?: "Не удалось сохранить Client ID"
                            } finally {
                                savingJamendo = false
                            }
                        }
                    },
                    enabled = !savingJamendo && jamendo != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (savingJamendo) "Сохраняем…" else "Сохранить Client ID")
                }
                Text(
                    "Получить ID: devportal.jamendo.com",
                    color = Muted,
                    fontSize = 11.sp
                )
                jamendoMessage?.let { Text(it, color = Muted, fontSize = 11.sp) }
            }
        }
        OutlinedButton(
            onClick = { scope.launch { refreshStatuses() } },
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (refreshing) "Проверяем источники…" else "Обновить состояние")
        }
        sourceRegistry.all().forEach { descriptor ->
            val capabilities = buildList {
                if (descriptor.capabilities.search) add("Поиск")
                if (descriptor.capabilities.library) add("Медиатека")
                if (descriptor.capabilities.playlists) add("Плейлисты")
                if (descriptor.capabilities.offlinePlayback) add("Офлайн")
                if (descriptor.capabilities.authentication) add("Авторизация")
            }
            Surface(
                color = Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(descriptor.displayName, color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
                    Text(statuses[descriptor.id] ?: "Проверяем…", color = Muted, fontSize = 12.sp)
                    Text(
                        capabilities.joinToString(" · ").ifBlank { "Возможности не заявлены" },
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Text(
            "VK Music подключим только через поддерживаемый официальный доступ. Авторизация VK ID сама по себе не открывает музыкальный каталог.",
            color = Muted,
            fontSize = 12.sp
        )
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
                message = if (tracks.isEmpty()) "На устройстве пока не найдено музыкальных файлов" else "Найдено файлов: ${tracks.size}"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
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

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Медиатека устройства", color = LocalWavePalette.current.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Surface(
                color = Panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPlayTracks(tracks, index) }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.title, color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
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
    val sources = remember(sourceRegistry) {
        sourceRegistry.all().filter { it.capabilities.search }
    }
    var query by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(emptyList<SourceSearchGroup>()) }
    var loading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var resultQuery by remember { mutableStateOf("") }
    var filtersExpanded by remember { mutableStateOf(false) }
    var artistFilter by remember { mutableStateOf("") }
    var titleFilter by remember { mutableStateOf("") }
    var albumFilter by remember { mutableStateOf("") }
    var genreFilter by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("all") }
    var sortFilter by remember { mutableStateOf("relevance") }
    var durationFilter by remember { mutableStateOf("any") }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var paginationJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    val loadingMore = groups.any { it.loadingMore }
    val filteredGroups = groups.map { group ->
        val filtered = group.tracks.filter { track ->
            (artistFilter.isBlank() || track.artist.contains(artistFilter.trim(), ignoreCase = true)) &&
                (titleFilter.isBlank() || track.title.contains(titleFilter.trim(), ignoreCase = true)) &&
                (albumFilter.isBlank() || track.album?.contains(albumFilter.trim(), ignoreCase = true) == true) &&
                (genreFilter.isBlank() || track.genre?.contains(genreFilter.trim(), ignoreCase = true) == true) &&
                when (durationFilter) {
                    "short" -> track.durationMs != null && track.durationMs < 180_000L
                    "medium" -> track.durationMs != null && track.durationMs in 180_000L..600_000L
                    "long" -> track.durationMs != null && track.durationMs > 600_000L
                    else -> true
                }
        }.let { list -> when (sortFilter) {
            "title" -> list.sortedBy { it.title.lowercase() }
            "artist" -> list.sortedBy { it.artist.lowercase() }
            else -> list
        } }
        group.copy(tracks = filtered)
    }.filter { sourceFilter == "all" || it.source.id == sourceFilter }

    fun loadNextPage(group: SourceSearchGroup) {
        val token = group.nextPageToken ?: return
        if (loading || group.loadingMore || paginationJob?.isActive == true) return
        val searchQuery = resultQuery
        if (searchQuery.isEmpty() || query.trim() != resultQuery) return
        val generation = searchGeneration
        groups = groups.map { current ->
            if (current.source.id == group.source.id) current.copy(loadingMore = true, message = null)
            else current
        }
        paginationJob = scope.launch {
            try {
                val provider = sourceRegistry.get(group.source.id)
                    ?: throw IllegalStateException("Источник отключён")
                val page = provider.search(searchQuery, token)
                if (generation != searchGeneration) return@launch
                groups = groups.map { current ->
                    if (current.source.id != group.source.id) current
                    else current.copy(
                        tracks = (current.tracks + page.tracks).distinctBy { it.id },
                        nextPageToken = page.nextPageToken,
                        loadingMore = false,
                        message = if (page.tracks.isEmpty()) "Больше результатов нет" else null
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != searchGeneration) return@launch
                groups = groups.map { current ->
                    if (current.source.id == group.source.id) current.copy(
                        loadingMore = false,
                        message = error.message ?: "Не удалось загрузить следующую страницу"
                    ) else current
                }
            }
        }
    }

    LazyColumn(
        modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "search-title") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Поиск музыки", color = LocalWavePalette.current.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Один запрос по всем подключённым каталогам", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        if (value != query) {
                            query = value
                            searchGeneration += 1
                            searchJob?.cancel()
                            paginationJob?.cancel()
                            loading = false
                            hasSearched = false
                            groups = emptyList()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Трек или исполнитель") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (filtersExpanded) "Скрыть фильтры" else "Фильтры поиска")
                    Spacer(Modifier.weight(1f))
                    Text(if (artistFilter.isNotBlank() || titleFilter.isNotBlank() || albumFilter.isNotBlank() || genreFilter.isNotBlank() || sourceFilter != "all" || sortFilter != "relevance" || durationFilter != "any") "Активны" else "Все")
                }
                if (filtersExpanded) {
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Уточнить результаты", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(value = artistFilter, onValueChange = { artistFilter = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Исполнитель") })
                            OutlinedTextField(value = titleFilter, onValueChange = { titleFilter = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название трека") })
                            OutlinedTextField(value = albumFilter, onValueChange = { albumFilter = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Альбом") })
                            OutlinedTextField(value = genreFilter, onValueChange = { genreFilter = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Жанр") })
                            Text("Источник", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.Medium)
                            var sourceMenu by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (sourceFilter == "all") "Все источники" else sources.firstOrNull { it.id == sourceFilter }?.displayName ?: "Все источники")
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                    DropdownMenuItem(text = { Text("Все источники") }, onClick = { sourceFilter = "all"; sourceMenu = false })
                                    sources.forEach { descriptor ->
                                        DropdownMenuItem(text = { Text(descriptor.displayName) }, onClick = { sourceFilter = descriptor.id; sourceMenu = false })
                                    }
                                }
                            }
                            Text("Длительность", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.Medium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("any" to "Любая", "short" to "До 3 мин", "medium" to "3–10 мин", "long" to "Более 10 мин").forEach { (id, label) ->
                                    FilterChip(selected = durationFilter == id, onClick = { durationFilter = id }, label = { Text(label, fontSize = 10.sp) })
                                }
                            }
                            Text("Сортировка", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = sortFilter == "relevance", onClick = { sortFilter = "relevance" }, label = { Text("Релевантность") })
                                FilterChip(selected = sortFilter == "title", onClick = { sortFilter = "title" }, label = { Text("Название") })
                                FilterChip(selected = sortFilter == "artist", onClick = { sortFilter = "artist" }, label = { Text("Исполнитель") })
                            }
                            Text("Фильтры по альбому, жанру и длительности применяются, если источник передал эти данные.", color = Muted, fontSize = 11.sp)
                            TextButton(onClick = {
                                artistFilter = ""; titleFilter = ""; albumFilter = ""; genreFilter = ""
                                sourceFilter = "all"; sortFilter = "relevance"; durationFilter = "any"
                            }, modifier = Modifier.align(Alignment.End)) { Text("Сбросить фильтры") }
                        }
                    }
                }
                Button(
                    onClick = {
                        val searchQuery = query.trim()
                        if (searchQuery.isNotEmpty()) {
                            searchGeneration += 1
                            val generation = searchGeneration
                            searchJob?.cancel()
                            paginationJob?.cancel()
                            resultQuery = searchQuery
                            groups = emptyList()
                            searchJob = scope.launch {
                                loading = true
                                hasSearched = true
                                try {
                                    val results = coroutineScope {
                                        sources.filter { sourceFilter == "all" || it.id == sourceFilter }.map { descriptor ->
                                            async {
                                                val provider = sourceRegistry.get(descriptor.id)
                                                    ?: return@async SourceSearchGroup(descriptor, message = "Источник отключён")
                                                try {
                                                    when (val state = provider.connectionState()) {
                                                        SourceConnectionState.Available -> {
                                                            val page = provider.search(searchQuery)
                                                            SourceSearchGroup(
                                                                descriptor,
                                                                page.tracks,
                                                                nextPageToken = page.nextPageToken
                                                            )
                                                        }
                                                        SourceConnectionState.NeedsPermission ->
                                                            SourceSearchGroup(descriptor, message = "Нужно разрешение Android")
                                                        SourceConnectionState.NeedsAuthentication ->
                                                            SourceSearchGroup(descriptor, message = "Требуется авторизация")
                                                        is SourceConnectionState.NeedsConfiguration ->
                                                            SourceSearchGroup(descriptor, message = state.reason)
                                                        is SourceConnectionState.Unavailable ->
                                                            SourceSearchGroup(descriptor, message = state.reason)
                                                    }
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    SourceSearchGroup(descriptor, message = error.message ?: "Ошибка поиска")
                                                }
                                            }
                                        }.awaitAll()
                                    }
                                    if (generation == searchGeneration) groups = results
                                } finally {
                                    if (generation == searchGeneration) loading = false
                                }
                            }
                        }
                    },
                    enabled = !loading && !loadingMore && query.isNotBlank() && sources.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Ищем во всех каталогах…" else "Искать везде")
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (groups.isNotEmpty() && query.trim() != resultQuery) {
                    Text("Результаты для «$resultQuery». Нажми «Искать везде», чтобы обновить.", color = Muted, fontSize = 11.sp)
                }
                if (hasSearched && !loading && filteredGroups.all { it.tracks.isEmpty() }) {
                    Text(if (groups.any { it.tracks.isNotEmpty() }) "По текущим фильтрам ничего не найдено" else "Ничего не найдено в доступных источниках", color = Muted, fontSize = 13.sp)
                }
                if (filteredGroups.sumOf { it.tracks.size } > 0) {
                    Text("Найдено после фильтрации: ${filteredGroups.sumOf { it.tracks.size }}", color = Muted, fontSize = 11.sp)
                }
            }
        }

        filteredGroups.forEach { group ->
            item(key = "source-header:${group.source.id}") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(group.source.displayName, color = LocalWavePalette.current.foreground, fontWeight = FontWeight.Bold)
                    if (group.tracks.isNotEmpty()) {
                        Text("${group.tracks.size} треков", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            group.message?.let { message ->
                item(key = "source-message:${group.source.id}") {
                    Text(message, color = Muted, fontSize = 12.sp)
                }
            }
            itemsIndexed(
                items = group.tracks,
                key = { _, track -> "track:${group.source.id}:${track.id}" }
            ) { index, track ->
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onPlayTracks(group.tracks, index) }
                ) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(track.title, color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
                            Text(track.artist, color = Muted, fontSize = 12.sp)
                            val details = listOfNotNull(track.album, track.genre, track.year?.toString()).joinToString(" · ")
                            if (details.isNotBlank()) Text(details, color = Muted, fontSize = 10.sp)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = "Слушать", tint = Accent)
                    }
                }
            }
            if (group.nextPageToken != null || group.loadingMore) {
                item(key = "source-pagination:${group.source.id}") {
                    OutlinedButton(
                        onClick = { loadNextPage(group) },
                        enabled = !loading && !group.loadingMore && !loadingMore && query.trim() == resultQuery,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (group.loadingMore) "Загружаем…" else "Ещё результаты")
                    }
                }
            }
        }
    }
}
@Composable
private fun QueueScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier
) {
    var revision by remember(controller) { mutableIntStateOf(0) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                revision++
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                revision++
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                revision++
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    val queue = remember(controller, revision) {
        controller?.let { player ->
            List(player.mediaItemCount) { index -> player.getMediaItemAt(index) }
        } ?: emptyList()
    }
    val currentIndex = controller?.currentMediaItemIndex ?: -1
    val canChange = controller?.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) == true

    LazyColumn(
        modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "queue-header") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Очередь воспроизведения", color = LocalWavePalette.current.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${queue.size} треков", color = Muted, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { if (canChange) controller?.clearMediaItems() },
                    enabled = canChange && queue.isNotEmpty()
                ) {
                    Text("Очистить")
                }
            }
        }

        if (controller != null) {
            item(key = "queue-repeat") {
                val repeatLabel = when (controller.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Повтор: трек"
                    Player.REPEAT_MODE_ALL -> "Повтор: очередь"
                    else -> "Повтор: выкл."
                }
                OutlinedButton(
                    onClick = {
                        if (controller.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
                            val next = when (controller.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            controller.repeatMode = next
                        }
                    },
                    enabled = controller.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(repeatLabel)
                }
            }
        }

        if (controller == null) {
            item(key = "queue-empty") {
                Text("Подключаем плеер…", color = Muted)
            }
        } else if (queue.isEmpty()) {
            item(key = "queue-empty") {
                Text("Очередь пока пуста. Найди музыку или запусти тест движка.", color = Muted)
            }
        } else {
            itemsIndexed(
                items = queue,
                key = { index, item -> "queue:${index}:${item.mediaId}" }
            ) { index, item ->
                val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Без названия"
                val artist = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель"
                val selected = index == currentIndex
                Surface(
                    color = if (selected) Accent.copy(alpha = .16f) else Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (controller.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) {
                            controller.seekTo(index, 0L)
                            controller.play()
                        }
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (selected) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                            contentDescription = if (selected) "Сейчас играет" else null,
                            tint = if (selected) Accent else Muted
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, color = LocalWavePalette.current.foreground, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            Text(artist, color = Muted, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = { if (canChange && index > 0) controller.moveMediaItem(index, index - 1) },
                            enabled = canChange && index > 0
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Переместить выше", tint = Muted)
                        }
                        IconButton(
                            onClick = { if (canChange && index < queue.lastIndex) controller.moveMediaItem(index, index + 1) },
                            enabled = canChange && index < queue.lastIndex
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Переместить ниже", tint = Muted)
                        }
                        IconButton(
                            onClick = { if (canChange) controller.removeMediaItem(index) },
                            enabled = canChange
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить из очереди", tint = Muted)
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    title: String,
    artist: String,
    hasMedia: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit
) {
    LazyColumn(
        modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ТВОЯ МУЗЫКА", color = Accent, fontSize = 11.sp,
                    letterSpacing = 2.4.sp, fontWeight = FontWeight.Bold)
                Text("С возвращением", color = LocalWavePalette.current.foreground,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Что будем слушать сегодня?", color = Muted, fontSize = 14.sp)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(
                        listOf(Color(0xFF49377B), Color(0xFF252D4A), Color(0xFF171B2B))
                    )).clickable { if (hasMedia) onOpenPlayer() else onSearch() }
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("СЕЙЧАС В ЭФИРЕ", color = Color.White.copy(alpha = .72f),
                        fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.GraphicEq, contentDescription = null,
                        tint = Color.White.copy(alpha = .8f))
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(86.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFC29AFF), Color(0xFF536C9E)))),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(42.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (hasMedia) title else "Музыка ждёт тебя",
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2)
                        Text(if (hasMedia) artist else "Найди любимые треки",
                            color = Color.White.copy(alpha = .72f), fontSize = 12.sp, maxLines = 1)
                        Text(if (hasMedia) "ОТКРЫТЬ ПЛЕЕР" else "ПЕРЕЙТИ К ПОИСКУ",
                            color = Color(0xFFD5C3FF), fontSize = 10.sp,
                            letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledIconButton(onClick = { if (hasMedia) onPlay() else onSearch() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White, contentColor = Color(0xFF29213F)
                        ), modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Пауза" else "Воспроизвести")
                    }
                }
            }
        }
        item {
            Text("Быстрый доступ", color = LocalWavePalette.current.foreground,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeQuickAction("Поиск музыки", "Найди новый трек", Icons.Default.Search,
                    Modifier.weight(1f), onSearch)
                HomeQuickAction("Медиатека", "Музыка на устройстве", Icons.Default.LibraryMusic,
                    Modifier.weight(1f), onLibrary)
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpenPlayer() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Headphones, contentDescription = null, tint = Accent,
                        modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Твой музыкальный поток", color = LocalWavePalette.current.foreground,
                            fontWeight = FontWeight.SemiBold)
                        Text("Управляй воспроизведением и очередью", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun HomeQuickAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp),
        modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Text(title, color = LocalWavePalette.current.foreground,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    playing: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Accent, Color(0xFF3B6D83)))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = LocalWavePalette.current.foreground,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                Text(artist, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            IconButton(onClick = onPlay) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Пауза" else "Воспроизвести",
                    tint = LocalWavePalette.current.foreground)
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.ExpandLess, contentDescription = "Открыть плеер",
                    tint = Accent)
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
        Icon(Icons.Default.GraphicEq, contentDescription = "Wave Player", tint = Color.White)
    }
}

@Composable
private fun WaveHeader(tab: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("WAVE PLAYER", color = LocalWavePalette.current.foreground, fontSize = 12.sp,
                letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Text(tab, color = LocalWavePalette.current.foreground, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
    crossfadeProgress: Float?,
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
            if (crossfadeProgress != null) {
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("СМЕШИВАНИЕ ТРЕКОВ", color = LocalWavePalette.current.foreground,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("${(crossfadeProgress * 100).toInt()}%", color = Accent,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = crossfadeProgress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = Accent,
                        trackColor = Muted.copy(alpha = .25f)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(track.first, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = LocalWavePalette.current.foreground)
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
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий трек", tint = if (canSkipPrevious) LocalWavePalette.current.foreground else Muted)
                }
                FilledIconButton(onClick = onPlay, enabled = hasMedia,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
                    modifier = Modifier.size(58.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Пауза демо" else "Запуск демо",
                        modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onNext, enabled = canSkipNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Следующий трек", tint = if (canSkipNext) LocalWavePalette.current.foreground else Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (hasMedia) "УПРАВЛЕНИЕ ЧЕРЕЗ ANDROID MEDIA SESSION" else "НЕТ ДОСТУПНОГО ИСТОЧНИКА АУДИО",
                color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
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
                    Text("Плавный переход", color = LocalWavePalette.current.foreground, fontWeight = FontWeight.SemiBold)
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
