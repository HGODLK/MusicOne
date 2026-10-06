package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** 每个歌词窗口只接收自己的进度，其他歌曲的归零不能让旧列表倒滚。 */
internal class LyricWindowPlayback(private val trackId: String) {
    var positionMs by mutableLongStateOf(0L)
        private set

    fun update(snapshot: PlaybackProgressSnapshot, previewMs: Long? = null, browsing: Boolean = false) {
        if (!browsing && snapshot.trackId == trackId) {
            positionMs = (previewMs ?: snapshot.positionMs).coerceAtLeast(0L)
        }
    }

    // 尚未真正切走的当前歌曲可以原位接回；已播放过的旧窗口不能冒充从头起播的新窗口。
    fun canReturnTo(playbackTrackId: String?, restarting: Boolean = false): Boolean =
        (!restarting && trackId == playbackTrackId) || positionMs == 0L
}
