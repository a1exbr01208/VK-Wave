package com.vkwave.app

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101116)
private val Panel = Color(0xFF1B1D25)
private val Accent = Color(0xFF9B7BFF)
private val Muted = Color(0xFF9699A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VKWaveApp() }
    }
}

@Composable
fun VKWaveApp() {
    var tab by remember { mutableStateOf("Главная") }
    var crossfade by remember { mutableFloatStateOf(7f) }
    var playing by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val tracks = listOf(
        "Midnight City" to "M83",
        "After Dark" to "Mr.Kitty",
        "Resonance" to "HOME",
        "The Perfect Girl" to "Mareux"
    )
    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Row(Modifier.fillMaxSize().background(Ink)) {
            Column(
                Modifier.width(76.dp).fillMaxHeight().padding(vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF43C6AC)))),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color.White)
                }
                listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.LibraryMusic, Icons.Default.Settings).forEachIndexed { i, icon ->
                    IconButton(onClick = { tab = listOf("Главная", "Поиск", "Медиатека", "Настройки")[i] }) {
                        Icon(icon, null, tint = if (tab == listOf("Главная", "Поиск", "Медиатека", "Настройки")[i]) Accent else Muted)
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.AccountCircle, null, tint = Muted, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VK WAVE", color = Color.White, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                        Text(tab, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = Panel, shape = CircleShape) {
                        Row(Modifier.clickable { }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, null, tint = Accent, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("VK не подключён", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF35305B), Color(0xFF1C2738), Color(0xFF171922))))) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(180.dp).clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFFB58CFF), Color(0xFF436B9C), Color(0xFF20243C)))),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.GraphicEq, null, tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(76.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(tracks[selected].first, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(tracks[selected].second, color = Muted, fontSize = 15.sp)
                        Spacer(Modifier.height(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selected = (selected - 1 + tracks.size) % tracks.size }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                            FilledIconButton(onClick = { playing = !playing }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent), modifier = Modifier.size(58.dp)) {
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(30.dp))
                            }
                            IconButton(onClick = { selected = (selected + 1) % tracks.size }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
                        }
                        Text("ДЕМО-ИНТЕРФЕЙС · АУДИО ЕЩЁ НЕ ПОДКЛЮЧЕНО", color = Color.White.copy(alpha = .55f), fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Плавный переход", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("Наложение двух треков", color = Muted, fontSize = 12.sp)
                            }
                            Text("${crossfade.toInt()} сек.", color = Accent, fontWeight = FontWeight.Bold)
                        }
                        Slider(value = crossfade, onValueChange = { crossfade = it }, valueRange = 2f..15f, steps = 12)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("2 сек.", color = Muted, fontSize = 11.sp)
                            Text("15 сек.", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
