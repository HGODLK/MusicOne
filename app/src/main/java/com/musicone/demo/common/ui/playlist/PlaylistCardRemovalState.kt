package com.musicone.demo

import androidx.compose.runtime.*

/** 写入结果可以先到达，网格中的占位一直保留到编辑浮层真正退出。 */
@Stable
internal class PlaylistCardRemovalState {
    private var held by mutableStateOf<MusicPlaylist?>(null)
    private var heldIndex = 0
    private var excluded by mutableStateOf(emptySet<String>())
    private var deletingId by mutableStateOf<String?>(null)

    fun hold(playlist: MusicPlaylist, index: Int) {
        held = playlist
        heldIndex = index.coerceAtLeast(0)
        deletingId = null
    }

    fun confirmDelete(id: String) { deletingId = id }

    fun hidden(id: String): Boolean = id == deletingId || id in excluded

    fun present(source: List<MusicPlaylist>): List<MusicPlaylist> {
        val remaining = source.filterNot { it.id in excluded }
        val retained = held ?: return remaining
        if (remaining.any { it.id == retained.id }) return remaining
        return remaining.toMutableList().apply { add(heldIndex.coerceAtMost(size), retained) }
    }

    fun finish(): String? {
        val removed = deletingId
        if (removed != null) excluded = excluded + removed
        held = null
        deletingId = null
        return removed
    }
}
