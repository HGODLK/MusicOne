package com.musicone

/** 记录本次播放会话的连续播放时长和网络卡顿次数，仅存内存不持久化。 */
internal class PlaybackPreloadTiming {
    var bufferingCount = 0
        private set
    val conservative get() = bufferingCount >= 3
    private var foreground = false
    private var startedAt: Long? = null
    private var stableAt: Long? = null
    private var wasPlaying = false
    private var bufferingAt: Long? = null
    private var bandwidthEvidence = false

    fun foregroundChanged(visible: Boolean): Boolean {
        val entering = visible && !foreground
        foreground = visible
        if (entering) {
            bufferingCount = 0
            newTrack()
        }
        return entering
    }

    fun newTrack() { startedAt = null; interrupt() }

    /** 主动拖动进度、暂停、切音质或报错时重置计时，别把这些算成网络卡顿。 */
    fun interrupt() {
        stableAt = null
        wasPlaying = false
        bufferingAt = null
        bandwidthEvidence = false
    }

    fun observe(nowMs: Long, playing: Boolean, buffering: Boolean, eligible: Boolean,
        bandwidthInsufficient: Boolean, transferFailed: Boolean = false) {
        if (!eligible || transferFailed) { interrupt(); return }
        if (playing) {
            val began = bufferingAt
            if (began != null && nowMs - began >= 1_000L && bandwidthEvidence) {
                bufferingCount = (bufferingCount + 1).coerceAtMost(3)
            }
            bufferingAt = null
            bandwidthEvidence = false
            if (startedAt == null) startedAt = nowMs
            if (stableAt == null) stableAt = nowMs
        } else if (buffering) {
            if (wasPlaying) bufferingAt = nowMs
            if (bufferingAt != null && bandwidthInsufficient) bandwidthEvidence = true
            stableAt = null
        } else interrupt()
        wasPlaying = playing
    }

    fun elapsedMs(nowMs: Long): Long =
        (if (conservative) stableAt else startedAt)?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    fun downloadDelayMs(index: Int): Long? = playbackDownloadDelayMs(index, conservative)
}
