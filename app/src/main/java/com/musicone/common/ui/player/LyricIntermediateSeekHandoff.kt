package com.musicone

/** 保护开合中途拖动进度条松手后、播放器正式进度尚未追上之前的过渡期，防止歌词闪回旧进度。 */
internal class LyricIntermediateSeekHandoff {
    private var generation: Long? = null
    private var trackId: String? = null
    private var observedRevision = 0L
    var pendingCommit: ProgressLyricSeekCommit? = null
        private set
    var acceptedCommit: ProgressLyricSeekCommit? = null
        private set

    fun update(observed: PlaybackProgressSnapshot, observedPreview: Long?,
        latest: PlaybackProgressSnapshot, latestPreview: Long?, intermediate: Boolean,
        enabled: Boolean, observedCommit: ProgressLyricSeekCommit? = null,
        latestCommit: ProgressLyricSeekCommit? = null): Boolean {
        val newCommit = latestCommit != null && latestCommit.revision != observedRevision
        if (latestCommit != null) observedRevision = latestCommit.revision
        if (!enabled || generation != null &&
            (generation != latest.generation || trackId != latest.trackId)) clear()
        if (!enabled) return false

        // 松手瞬间实时预览可能已清空，但旧通知还在路上，这一帧仍要进入保护期。
        if (intermediate && (latestPreview != null || observedPreview != null)) {
            generation = latest.generation
            trackId = latest.trackId
        }
        if (latestCommit == null) {
            if (latestPreview != null) {
                pendingCommit = null
                acceptedCommit = null
            } else if (pendingCommit != null) clear()
        }
        if (latestCommit != null && latestCommit.trackId == latest.trackId &&
            latestCommit.generation == latest.generation &&
            (newCommit && (intermediate || generation != null) ||
                pendingCommit?.revision == latestCommit.revision)) {
            // 只接管新的 seek 提交或更新当前待确认的提交，忽略迟到的旧事件。
            generation = latest.generation
            trackId = latest.trackId
            pendingCommit = latestCommit
            acceptedCommit = latestCommit
        }
        if (generation == null) return false
        // 必须等播放器正式进度确实追上本次提交的目标且收到确认后，才解除保护。
        val pending = pendingCommit
        val progressCaughtUp = pending == null || lyricSeekCommitProgressCaughtUp(observed, pending)
        val confirmed = latestPreview == null && observedPreview == null && observed == latest && progressCaughtUp &&
            (pending == null || pending.confirmed != null && observedCommit?.let {
                it.revision == pending.revision && it.confirmed != null
            } == true)
        if (confirmed) clear(retainCommit = true)
        return true
    }

    private fun clear(retainCommit: Boolean = false) {
        generation = null
        trackId = null
        pendingCommit = null
        if (!retainCommit) acceptedCommit = null
    }
}

/** 判断播放器正式采样是否已追上本次 seek（仅接受提交之后的采样，且位置落在合理误差内）。 */
internal fun lyricSeekCommitProgressCaughtUp(observed: PlaybackProgressSnapshot,
    pending: ProgressLyricSeekCommit): Boolean {
    val confirmed = pending.confirmed ?: return false
    if (observed.trackId != pending.trackId || observed.generation != pending.generation ||
        observed.sampledAtMs < confirmed.sampledAtMs) return false
    val elapsed = observed.sampledAtMs - confirmed.sampledAtMs
    return observed.positionMs >= pending.positionMs - 250L &&
        observed.positionMs <= pending.positionMs + elapsed + 250L
}
