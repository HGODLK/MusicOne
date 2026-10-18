package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 收藏和普通歌单共用可撤销暂存；只有离页提交时才取出待写入项目。 */
internal class DeferredRemovals<T>(private val identity: (T) -> String) {
    private var entries by mutableStateOf(emptyMap<String, T>())
    val ids: Set<String> get() = entries.keys
    fun stage(value: T) { entries = entries + (identity(value) to value) }
    fun undo(id: String): T? = entries[id].also { entries = entries - id }
    fun drain(): List<T> = entries.values.toList().also { entries = emptyMap() }
}
