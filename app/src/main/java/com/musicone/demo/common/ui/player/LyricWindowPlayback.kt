package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** 每个歌词窗口只接收自己的进度，其他歌曲的归零不能让旧列表倒滚。 */
internal class LyricWindowPlayback(private val trackId: String) {
    private var awaitingObservedHandoff = true
    var positionMs by mutableLongStateOf(0L)
        private set
    var generation by mutableLongStateOf(-1L)
        private set

    // 屏外预览尚未绑定轮次；同曲重新起播后也不能接收上一轮保留的定位请求。
    fun progressSeek(request: ProgressLyricSeek?): ProgressLyricSeek? =
        request?.takeIf { it.trackId == trackId && it.generation == generation }

    fun updateObserved(snapshot: PlaybackProgressSnapshot, previewMs: Long?, browsing: Boolean,
        latest: PlaybackProgressSnapshot, latestPreviewMs: Long?, latestBrowsing: Boolean) {
        // 只在窗口接管或播放轮次不同步时读取实时值；同曲 seek 保留原页面通知节奏。
        val samePlayback = snapshot.trackId == latest.trackId &&
            snapshot.generation == latest.generation && browsing == latestBrowsing
        if (!samePlayback) awaitingObservedHandoff = true
        else if (previewMs == latestPreviewMs) awaitingObservedHandoff = false
        val handingOff = awaitingObservedHandoff || !samePlayback
        if (handingOff) update(latest, latestPreviewMs, latestBrowsing)
        else update(snapshot, previewMs, browsing)
    }

    fun update(snapshot: PlaybackProgressSnapshot, previewMs: Long? = null, browsing: Boolean = false) {
        if (!browsing && snapshot.trackId == trackId &&
            (generation < 0L || generation == snapshot.generation)) {
            generation = snapshot.generation
            positionMs = (previewMs ?: snapshot.positionMs).coerceAtLeast(0L)
        }
    }

    // 尚未真正切走的当前歌曲可以原位接回；已播放过的旧窗口不能冒充从头起播的新窗口。
    fun canReturnTo(snapshot: PlaybackProgressSnapshot, restarting: Boolean = false): Boolean =
        (!restarting && trackId == snapshot.trackId && generation == snapshot.generation) ||
            (generation < 0L && positionMs == 0L)
}
