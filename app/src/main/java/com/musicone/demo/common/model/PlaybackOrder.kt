package com.musicone.demo

enum class RepeatMode { ALL, ONE, OFF }

enum class TrackTransitionDirection { NEXT, PREVIOUS }

internal enum class PlayerPlaybackMode { LIST, SHUFFLE, SINGLE;
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal fun playerPlaybackMode(shuffle: Boolean, repeat: RepeatMode): PlayerPlaybackMode = when {
    shuffle -> PlayerPlaybackMode.SHUFFLE
    repeat == RepeatMode.ONE -> PlayerPlaybackMode.SINGLE
    else -> PlayerPlaybackMode.LIST
}

internal fun nextQueueIndex(current: Int, size: Int, shuffle: Boolean, repeat: RepeatMode, automatic: Boolean): Int? {
    if (size == 0) return null
    if (automatic && repeat == RepeatMode.ONE) return current
    // 随机模式已经改变可见队列，下一首必须按该队列前进，不能再次抽签。
    if (automatic && !shuffle && repeat == RepeatMode.OFF && current == size - 1) return null
    return (current + 1) % size
}
