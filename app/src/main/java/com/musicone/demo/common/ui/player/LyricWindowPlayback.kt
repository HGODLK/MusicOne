package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/** 每个歌词窗口只接收自己的进度，其他歌曲的归零不能让旧列表倒滚。 */
internal class LyricWindowPlayback(private val trackId: String) {
    private var awaitingObservedHandoff = true
    private val intermediateSeekHandoff = LyricIntermediateSeekHandoff()
    var positionMs by mutableLongStateOf(0L)
        private set
    var sampledAtMs by mutableLongStateOf(0L)
        private set
    var generation by mutableLongStateOf(-1L)
        private set
    var intermediateCommit by mutableStateOf<ProgressLyricSeekCommit?>(null)
        private set
    var intermediateCommitPending by mutableStateOf(false)
        private set
    var intermediatePositionOwned by mutableStateOf(false)

    // 屏外预览尚未绑定轮次；同曲重新起播后也不能接收上一轮保留的定位请求。
    fun progressSeek(request: ProgressLyricSeek?): ProgressLyricSeek? =
        request?.takeIf { it.trackId == trackId && it.generation == generation }

    fun updateObserved(snapshot: PlaybackProgressSnapshot, previewMs: Long?, browsing: Boolean,
        latest: PlaybackProgressSnapshot, latestPreviewMs: Long?, latestBrowsing: Boolean,
        intermediateSeek: Boolean = false, active: Boolean = true,
        observedCommit: ProgressLyricSeekCommit? = null, latestCommit: ProgressLyricSeekCommit? = null) {
        val protectSeek = intermediateSeekHandoff.update(snapshot, previewMs, latest,
            latestPreviewMs, intermediateSeek || intermediatePositionOwned, active && !browsing && !latestBrowsing &&
                latest.trackId == trackId && (generation < 0L || generation == latest.generation),
            observedCommit, latestCommit)
        // 只在窗口接管或播放轮次不同步时读取实时值；同曲 seek 保留原页面通知节奏。
        val samePlayback = snapshot.trackId == latest.trackId &&
            snapshot.generation == latest.generation && browsing == latestBrowsing
        if (!samePlayback) awaitingObservedHandoff = true
        else if (previewMs == latestPreviewMs) awaitingObservedHandoff = false
        val handingOff = awaitingObservedHandoff || !samePlayback
        Snapshot.withMutableSnapshot {
            // 目标与时间同时发布，行内强调和列表位置不能各接到一半交接状态。
            intermediateCommit = intermediateSeekHandoff.acceptedCommit
            intermediateCommitPending = intermediateSeekHandoff.pendingCommit != null
            if (handingOff || protectSeek) update(latest, intermediateSeekHandoff.pendingCommit?.positionMs ?: latestPreviewMs, latestBrowsing)
            else update(snapshot, previewMs, browsing)
        }
    }

    fun update(snapshot: PlaybackProgressSnapshot, previewMs: Long? = null, browsing: Boolean = false) {
        if (!browsing && snapshot.trackId == trackId &&
            (generation < 0L || generation == snapshot.generation)) {
            generation = snapshot.generation
            positionMs = (previewMs ?: snapshot.positionMs).coerceAtLeast(0L)
            sampledAtMs = snapshot.sampledAtMs
        }
    }

    // 尚未真正切走的当前歌曲可以原位接回；已播放过的旧窗口不能冒充从头起播的新窗口。
    fun canReturnTo(snapshot: PlaybackProgressSnapshot, restarting: Boolean = false): Boolean =
        (!restarting && trackId == snapshot.trackId && generation == snapshot.generation) ||
            (generation < 0L && positionMs == 0L)

    // 词内时钟可直接使用同轮次的最新采样；定位事务仍保留窗口持有的目标。
    fun wordSnapshot(latest: PlaybackProgressSnapshot, previewMs: Long?): PlaybackProgressSnapshot =
        if (latest.trackId == trackId && latest.generation == generation &&
            !intermediateCommitPending && !intermediatePositionOwned) {
            latest.copy(positionMs = previewMs ?: latest.positionMs)
        } else PlaybackProgressSnapshot(trackId, positionMs, generation, sampledAtMs)
}
