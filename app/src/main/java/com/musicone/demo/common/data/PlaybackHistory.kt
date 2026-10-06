package com.musicone.demo

internal class PlaybackHistory {
    private val trackIds = ArrayDeque<String>()

    fun clear() = trackIds.clear()
    fun peekPrevious(availableTrackIds: Set<String>): String? = trackIds.lastOrNull { it in availableTrackIds }

    fun remember(currentTrackId: String?, nextTrackId: String) {
        val current = currentTrackId?.takeIf { it.isNotBlank() && it != nextTrackId } ?: return
        if (trackIds.lastOrNull() != current) trackIds.addLast(current)
    }

    fun takePrevious(availableTrackIds: Set<String>): String? {
        while (trackIds.isNotEmpty()) {
            val trackId = trackIds.removeLast()
            if (trackId in availableTrackIds) return trackId
        }
        return null
    }
}
