package com.musicone

/** 统计真正推进的墙钟时长，seek位置和暂停时间不算已经收听。 */
internal class PlaybackPlayedDuration {
    private var generation = Long.MIN_VALUE
    private var previousMs = 0L
    private var advancing = false
    private var totalMs = 0L
    fun record(request: Long, playing: Boolean, nowMs: Long = android.os.SystemClock.elapsedRealtime()): Long {
        if (request != generation) { generation = request; totalMs = 0L; advancing = false }
        if (advancing) totalMs += (nowMs - previousMs).coerceAtLeast(0L)
        previousMs = nowMs
        advancing = playing
        return totalMs
    }
}
