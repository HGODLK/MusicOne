package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** “我喜欢”和普通歌单共用的延迟移除暂存表，期间可以随时撤销，离开页面时再统一提交删除。 */
internal class DeferredRemovals<T>(private val identity: (T) -> String) {
    private var entries by mutableStateOf(emptyMap<String, T>())
    val ids: Set<String> get() = entries.keys
    fun stage(value: T) { entries = entries + (identity(value) to value) }
    fun undo(id: String): T? = entries[id].also { entries = entries - id }
    fun drain(): List<T> = entries.values.toList().also { entries = emptyMap() }
}
