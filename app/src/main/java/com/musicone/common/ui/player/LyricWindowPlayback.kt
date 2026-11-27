package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/** 单个歌词窗口的播放进度状态：只接收当前歌曲和当前播放轮次的进度，防止切歌归零时把旧歌词滚回开头。 */
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

    // 只响应当前歌曲且属于同一播放轮次的进度条 seek 请求。
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
        // 刚接管窗口或播放轮次未同步时直接读最新进度；同曲正常播放沿用订阅流快照。
        val samePlayback = snapshot.trackId == latest.trackId &&
            snapshot.generation == latest.generation && browsing == latestBrowsing
        if (!samePlayback) awaitingObservedHandoff = true
        else if (previewMs == latestPreviewMs) awaitingObservedHandoff = false
        val handingOff = awaitingObservedHandoff || !samePlayback
        Snapshot.withMutableSnapshot {
            // 在同一快照事务里同步更新提交状态和播放时间，避免高亮与列表滚动读到不一致的状态。
            intermediateCommit = intermediateSeekHandoff.acceptedCommit
            intermediateCommitPending = intermediateSeekHandoff.pendingCommit != null
            if (handingOff || protectSeek) update(latest, intermediateSeekHandoff.pendingCommit?.positionMs ?: latestPreviewMs, latestBrowsing)
            else update(snapshot, previewMs, browsing)
        }
    }

    /** 同曲新一轮播放接管本窗口：丢弃上一轮的定位交接，按新轮次重新接收进度。 */
    fun rebind(snapshot: PlaybackProgressSnapshot) {
        if (snapshot.trackId != trackId) return
        Snapshot.withMutableSnapshot {
            awaitingObservedHandoff = true
            intermediatePositionOwned = false
            intermediateCommit = null
            intermediateCommitPending = false
            generation = snapshot.generation
            positionMs = snapshot.positionMs.coerceAtLeast(0L)
            sampledAtMs = snapshot.sampledAtMs
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

    // 快速切回时判断是否可以复用退场中的旧窗口：同曲同轮次，或从未开始播放过的 0ms 窗口才允许接回。
    fun canReturnTo(snapshot: PlaybackProgressSnapshot, restarting: Boolean = false): Boolean =
        (!restarting && trackId == snapshot.trackId && generation == snapshot.generation) ||
            (generation < 0L && positionMs == 0L)

    // 非中间态保护期时，逐字时钟直接读当前轮次的最新采样；保护期内则沿用窗口锁定的进度。
    fun wordSnapshot(latest: PlaybackProgressSnapshot, previewMs: Long?): PlaybackProgressSnapshot =
        if (latest.trackId == trackId && latest.generation == generation &&
            !intermediateCommitPending && !intermediatePositionOwned) {
            latest.copy(positionMs = previewMs ?: latest.positionMs)
        } else PlaybackProgressSnapshot(trackId, positionMs, generation, sampledAtMs)
}
