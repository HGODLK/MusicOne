package com.musicone

import kotlin.math.abs

internal const val LYRIC_WORD_CLOCK_JUMP_MS = 300L

/** 判断词时钟是否被跳转：位置变化超过阈值且和实际流逝时间对不上。卡顿和暂停恢复都不算。 */
internal class LyricWordClockJump(private val nowNanos: () -> Long) {
    private var position: Long? = null
    private var sampledAtNanos = 0L

    fun update(next: Long?): Boolean {
        val now = nowNanos()
        val previous = position
        val elapsedMs = (now - sampledAtNanos) / 1_000_000L
        position = next
        sampledAtNanos = now
        if (previous == null || next == null) return false
        val moved = next - previous
        return abs(moved) > LYRIC_WORD_CLOCK_JUMP_MS && abs(moved - elapsedMs) > LYRIC_WORD_CLOCK_JUMP_MS
    }
}
