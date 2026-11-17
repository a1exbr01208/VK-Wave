package com.vkwave.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates two original synthetic WAV tones for exercising playback,
 * MediaSession, seeking and crossfade before a real catalog is connected.
 */
class DemoAudioProvider(context: Context) {
    private val appContext = context.applicationContext

    suspend fun createPlaylist(): List<MediaItem> = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, "vk_wave_demo_audio").apply { mkdirs() }
        val first = createTone(directory, "tone_440.wav", 440.0)
        val second = createTone(directory, "tone_554.wav", 554.37)

        listOf(
            demoItem("demo-tone-440", "Тестовый тон 440 Гц", first),
            demoItem("demo-tone-554", "Тестовый тон 554 Гц", second)
        )
    }

    private fun demoItem(id: String, title: String, file: File): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist("VK Wave · технический тест")
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun createTone(directory: File, fileName: String, frequencyHz: Double): File {
        val sampleRate = 44_100
        val durationSeconds = 18
        val sampleCount = sampleRate * durationSeconds
        val dataSize = sampleCount * 2
        val file = File(directory, fileName)
        if (file.exists() && file.length() == 44L + dataSize) return file

        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.writeAscii("RIFF")
            output.writeIntLe(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeIntLe(16)
            output.writeShortLe(1)
            output.writeShortLe(1)
            output.writeIntLe(sampleRate)
            output.writeIntLe(sampleRate * 2)
            output.writeShortLe(2)
            output.writeShortLe(16)
            output.writeAscii("data")
            output.writeIntLe(dataSize)

            val fadeSamples = sampleRate / 10
            for (sampleIndex in 0 until sampleCount) {
                val timeSeconds = sampleIndex.toDouble() / sampleRate
                val fadeIn = (sampleIndex.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                val fadeOut = ((sampleCount - sampleIndex).toDouble() / (fadeSamples * 2))
                    .coerceIn(0.0, 1.0)
                val envelope = minOf(fadeIn, fadeOut)
                val wave = sin(2.0 * PI * frequencyHz * timeSeconds)
                val pcm = (wave * envelope * 0.24 * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.writeShortLe(pcm)
            }
        }
        return file
    }

    private fun BufferedOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun BufferedOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun BufferedOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
