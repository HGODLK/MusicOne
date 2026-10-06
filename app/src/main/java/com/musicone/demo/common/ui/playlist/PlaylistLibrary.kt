package com.musicone.demo

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlaylistLibrary(context: Context) {
    private val preferences = context.getSharedPreferences("playlist_library", Context.MODE_PRIVATE)
    private val savedIds = MutableStateFlow(preferences.getStringSet("saved_ids", emptySet()).orEmpty().toSet())
    val ids = savedIds.asStateFlow()

    fun toggle(playlist: MusicPlaylist) {
        val next = savedIds.value.toMutableSet()
        if (!next.add(playlist.id)) next.remove(playlist.id)
        preferences.edit { putStringSet("saved_ids", next) }
        savedIds.value = next
    }
}
