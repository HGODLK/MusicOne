package com.musicone.demo

import android.os.SystemClock

/** 将输入事件的实际时间映射到动画纳秒时钟，避免主线程迟到后重新从零计时。 */
internal fun gestureReleaseTimeNanos(
    eventMillis: Long,
    nowMillis: Long = SystemClock.uptimeMillis(),
    nowNanos: Long = System.nanoTime(),
): Long = nowNanos - (nowMillis - eventMillis).coerceAtLeast(0L) * 1_000_000L
