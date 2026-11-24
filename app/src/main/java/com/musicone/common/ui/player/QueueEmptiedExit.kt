package com.musicone

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 队列被全部移出后的迷你条退场：声音和队列已经清空，画面先保留最后一首，
 * 等播放页、队列抽屉都离开后迷你条再滑走；结束后才回到真正的空状态。
 * 只认“从有歌变成队列为空”这一次转变，打开空队列或启动时没有歌都不会触发。
 */
@Stable
internal class QueueEmptiedExit {
    // 只在组合中读写的记录，不需要触发重组。
    var lastTrack: MusicTrack? = null
        private set
    var leaving by mutableStateOf(false)
        private set
    private var hadTrack = false

    /** 在组合中直接调用：清空的同一帧就进入退场，迷你条不会先空一帧。 */
    fun update(current: MusicTrack?, queueEmpty: Boolean) {
        if (current != null) {
            lastTrack = current
            hadTrack = true
            leaving = false
        } else if (hadTrack) {
            hadTrack = false
            leaving = queueEmpty
        }
    }

    fun finish() { leaving = false }
}
