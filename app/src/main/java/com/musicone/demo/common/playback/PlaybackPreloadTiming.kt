package com.musicone.demo

/** 仅本次前台会话生效；连续真实播放计时和网络卡顿计数不写入设置。 */
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

    /** 主动跳转、暂停、换音质、连接错误不能继承先前的网络卡顿候选。 */
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
