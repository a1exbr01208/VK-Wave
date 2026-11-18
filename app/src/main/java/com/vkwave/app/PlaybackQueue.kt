package com.vkwave.app

internal fun resolveQueueStartIndex(
    resolvedOriginalIndexes: List<Int>,
    requestedIndex: Int
): Int? {
    val requested = requestedIndex.coerceAtLeast(0)
    val nextPlayable = resolvedOriginalIndexes.indexOfFirst { it >= requested }
    return nextPlayable.takeIf { it >= 0 } ?: resolvedOriginalIndexes.lastIndex.takeIf { it >= 0 }
}

/** Repeat behavior is independent of the provider and playback engine. */
enum class QueueRepeatMode {
    OFF,
    ALL,
    ONE
}

/**
 * Small deterministic queue model. It owns ordering and selection only;
 * resolving an authorized stream URI belongs to a provider repository.
 */
class PlaybackQueue {
    private var items: List<WaveTrack> = emptyList()

    var currentIndex: Int = -1
        private set

    var repeatMode: QueueRepeatMode = QueueRepeatMode.OFF

    val size: Int
        get() = items.size

    val tracks: List<WaveTrack>
        get() = items

    val current: WaveTrack?
        get() = items.getOrNull(currentIndex)

    fun replace(newItems: List<WaveTrack>, startIndex: Int = 0) {
        items = newItems.toList()
        currentIndex = if (items.isEmpty()) -1 else startIndex.coerceIn(items.indices)
    }

    fun select(index: Int): WaveTrack? {
        if (index !in items.indices) return null
        currentIndex = index
        return current
    }

    fun next(): WaveTrack? {
        if (items.isEmpty() || currentIndex < 0) return null
        if (repeatMode == QueueRepeatMode.ONE) return current
        if (currentIndex < items.lastIndex) {
            currentIndex += 1
            return current
        }
        if (repeatMode == QueueRepeatMode.ALL) {
            currentIndex = 0
            return current
        }
        return null
    }

    fun previous(): WaveTrack? {
        if (items.isEmpty() || currentIndex < 0) return null
        if (currentIndex > 0) {
            currentIndex -= 1
            return current
        }
        if (repeatMode == QueueRepeatMode.ALL) {
            currentIndex = items.lastIndex
        }
        return current
    }
}
