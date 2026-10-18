package com.musicone.demo

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/** QQ 卡片顺序按账号和分区隔离，只改变 MusicOne 中的排列。 */
internal class PlaylistCardOrderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playlist_card_order", Context.MODE_PRIVATE)
    fun read(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, null) ?: "[]")
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())
    fun write(key: String, ids: List<String>) { preferences.edit { putString(key, JSONArray(ids).toString()) } }
}

internal fun reconcilePlaylistCardOrder(saved: List<String>, current: List<String>): List<String> {
    val available = current.toHashSet()
    val retained = saved.filter { it in available }.distinct()
    val existing = retained.toHashSet()
    return retained + current.filter { it !in existing }
}
