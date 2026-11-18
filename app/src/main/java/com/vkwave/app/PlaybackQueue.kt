package com.vkwave.app

/** Repeat behavior is independent of the provider and playback engine. */
enum class QueueRepeatMode {
    OFF,
    ALL,
    ONE
}

internal fun nextPlayableQueueIndexes(
    queueSize: Int,
    currentIndex: Int,
    repeatMode: QueueRepeatMode,
    unavailableIndexes: Set<Int> = emptySet()
): List<Int> {
    if (queueSize < 1 || currentIndex !in 0 until queueSize) return emptyList()
    if (queueSize == 1) {
        return if (repeatMode == QueueRepeatMode.ALL && currentIndex !in unavailableIndexes) {
            listOf(currentIndex)
        } else emptyList()
    }
    val ordered = buildList {
        addAll((currentIndex + 1 until queueSize).toList())
        if (repeatMode == QueueRepeatMode.ALL) addAll((0 until currentIndex).toList())
    }
    return ordered.filterNot(unavailableIndexes::contains)
}

internal fun previousPlayableQueueIndexes(
    queueSize: Int,
    currentIndex: Int,
    repeatMode: QueueRepeatMode,
    unavailableIndexes: Set<Int> = emptySet()
): List<Int> {
    if (queueSize < 1 || currentIndex !in 0 until queueSize) return emptyList()
    if (queueSize == 1) {
        return if (repeatMode == QueueRepeatMode.ALL && currentIndex !in unavailableIndexes) {
            listOf(currentIndex)
        } else emptyList()
    }
    val ordered = buildList {
        addAll((currentIndex - 1 downTo 0).toList())
        if (repeatMode == QueueRepeatMode.ALL) {
            addAll((queueSize - 1 downTo currentIndex + 1).toList())
        }
    }
    return ordered.filterNot(unavailableIndexes::contains)
}

internal fun resolveQueueStartIndex(
    resolvedOriginalIndexes: List<Int>,
    requestedIndex: Int
): Int? {
    val requested = requestedIndex.coerceAtLeast(0)
    val nextPlayable = resolvedOriginalIndexes.indexOfFirst { it >= requested }
    return nextPlayable.takeIf { it >= 0 } ?: resolvedOriginalIndexes.lastIndex.takeIf { it >= 0 }
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
        if (repeatMode == QueueRepeatMode.ONE ||
            (repeatMode == QueueRepeatMode.ALL && items.size == 1)
        ) return current
        val target = nextPlayableQueueIndexes(items.size, currentIndex, repeatMode).firstOrNull()
            ?: return null
        currentIndex = target
        return current
    }

    fun previous(): WaveTrack? {
        if (items.isEmpty() || currentIndex < 0) return null
        val target = previousPlayableQueueIndexes(items.size, currentIndex, repeatMode).firstOrNull()
            ?: return current
        currentIndex = target
        return current
    }
}
