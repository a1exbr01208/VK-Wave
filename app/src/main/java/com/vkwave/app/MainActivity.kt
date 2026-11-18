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
    WaveTab("Очередь", Icons.Default.QueueMusic),
    WaveTab("Настройки", Icons.Default.Settings)
)

class MainActivity : ComponentActivity() {
    private val mediaControllerState = mutableStateOf<MediaController?>(null)
    private val crossfadeState = mutableFloatStateOf(7f)
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
                ::playSourceTracks
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
                        "Очередь" -> QueueScreen(controller, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
                        "Настройки" -> SettingsScreen(sourceRegistry, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
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
                            "Очередь" -> QueueScreen(controller, Modifier.weight(1f).fillMaxWidth())
                            "Настройки" -> SettingsScreen(sourceRegistry, Modifier.weight(1f).fillMaxWidth())
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
        Text("Настройки", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Подключённые музыкальные источники", color = Muted, fontSize = 13.sp)
        Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jamendo", color = Color.White, fontWeight = FontWeight.SemiBold)
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
                    Text(descriptor.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
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
    val sources = remember(sourceRegistry) {
        sourceRegistry.all().filter { it.capabilities.search }
    }
    var query by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(emptyList<SourceSearchGroup>()) }
    var loading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var resultQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var paginationJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    val loadingMore = groups.any { it.loadingMore }

    fun loadNextPage(group: SourceSearchGroup) {
        val token = group.nextPageToken ?: return
        if (loading || group.loadingMore) return
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

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Поиск музыки", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                                sources.map { descriptor ->
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
        if (hasSearched && !loading && groups.all { it.tracks.isEmpty() }) {
            Text("Ничего не найдено в доступных источниках", color = Muted, fontSize = 13.sp)
        }

        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(group.source.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                    if (group.tracks.isNotEmpty()) {
                        Text("${group.tracks.size} треков", color = Muted, fontSize = 11.sp)
                    }
                }
                group.message?.let { Text(it, color = Muted, fontSize = 12.sp) }
                group.tracks.forEachIndexed { index, track ->
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
                                Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(track.artist, color = Muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = "Слушать", tint = Accent)
                        }
                    }
                }
                if (group.nextPageToken != null || group.loadingMore) {
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

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Очередь воспроизведения", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${queue.size} треков", color = Muted, fontSize = 12.sp)
            }
            TextButton(
                onClick = { if (canChange) controller?.clearMediaItems() },
                enabled = canChange && queue.isNotEmpty()
            ) {
                Text("Очистить")
            }
        }

        if (controller != null) {
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

        if (controller == null) {
            Text("Подключаем плеер…", color = Muted)
        } else if (queue.isEmpty()) {
            Text("Очередь пока пуста. Найди музыку или запусти тест движка.", color = Muted)
        } else {
            queue.forEachIndexed { index, item ->
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
                            Text(title, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
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
