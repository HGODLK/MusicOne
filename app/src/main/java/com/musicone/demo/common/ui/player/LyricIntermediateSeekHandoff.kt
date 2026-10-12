package com.musicone.demo

/** 只保护中间态拖动的预览到正式进度交接，不改变普通 seek 的通知节奏。 */
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

        // 实时预览可能已清空，但页面尚在处理拖动通知，这一帧也必须接到正式目标。
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
            // 只接收新提交或补齐本次确认；迟到预览不能重新挂起已经交接完的旧提交。
            generation = latest.generation
            trackId = latest.trackId
            pendingCommit = latestCommit
            acceptedCommit = latestCommit
        }
        if (generation == null) return false
        // 本次提交完成后才能确认；页面正式进度必须确实追上且收到确认，不能在旧进度相等时提前解除。
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

/** 向后 seek 时旧进度也可能大于目标；只接受提交后合理推进的正式采样。 */
internal fun lyricSeekCommitProgressCaughtUp(observed: PlaybackProgressSnapshot,
    pending: ProgressLyricSeekCommit): Boolean {
    val confirmed = pending.confirmed ?: return false
    if (observed.trackId != pending.trackId || observed.generation != pending.generation ||
        observed.sampledAtMs < confirmed.sampledAtMs) return false
    val elapsed = observed.sampledAtMs - confirmed.sampledAtMs
    return observed.positionMs >= pending.positionMs - 250L &&
        observed.positionMs <= pending.positionMs + elapsed + 250L
}
