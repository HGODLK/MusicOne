package com.musicone.demo

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

internal class LyricTracePhase(val name: String, val cookie: Int) {
    var ended = false
}

private val lyricTraceSequence = AtomicInteger()

/** 仅在系统采样开启时记录动效阶段，不增加帧任务，也不记录歌曲或账号信息。 */
internal fun beginLyricTracePhase(name: String): LyricTracePhase? {
    if (Build.VERSION.SDK_INT < 29 || !Trace.isEnabled()) return null
    val phase = LyricTracePhase(name, lyricTraceSequence.incrementAndGet())
    Trace.beginAsyncSection(phase.name, phase.cookie)
    return phase
}

internal fun endLyricTracePhase(phase: LyricTracePhase?) {
    if (Build.VERSION.SDK_INT >= 29 && phase != null && !phase.ended) {
        phase.ended = true
        Trace.endAsyncSection(phase.name, phase.cookie)
    }
}

/** 记录实际布局或图层属性读取的位置，区别于应用其他区域提交的新帧。 */
internal inline fun lyricTracePosition(name: String, position: () -> Float) {
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
        Trace.setCounter(name, (position() * 1000f).toLong())
    }
}
