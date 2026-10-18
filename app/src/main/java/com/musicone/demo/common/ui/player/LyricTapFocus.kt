package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect

/** 点击焦点覆盖句首预留区间，逐字时间仍由真实播放时钟决定。 */
internal class LyricTapFocus {
    var line by mutableStateOf<Int?>(null)
        private set
    private var startMs = 0L
    private var seekMs = 0L

    fun request(index: Int, timeMs: Long, seekPositionMs: Long) {
        startMs = timeMs
        seekMs = seekPositionMs
        line = index
    }

    fun observe(positionMs: Long, framePositionMs: Long = positionMs) {
        // 进入句首后恢复跟随；其他定位跳出预留区间时也交还控制权。
        if ((positionMs >= startMs && framePositionMs >= startMs) || positionMs < seekMs - 1L) clear()
    }

    fun clear() { line = null }
}

@Composable
internal fun LyricTapFocusEffect(
    focus: LyricTapFocus,
    frameProgressMs: () -> Long,
    latestProgressMs: () -> Long,
    interrupted: Boolean,
    seekRevision: Long?,
) {
    val latestFrame by rememberUpdatedState(frameProgressMs)
    val latestProgress by rememberUpdatedState(latestProgressMs)
    LyricAnimationEffect(focus, interrupted, seekRevision) {
        focus.clear()
        if (interrupted) return@LyricAnimationEffect
        snapshotFlow {
            focus.line?.let { line ->
                // 正式帧时钟负责唤醒，实时采样避免倒退 seek 时误用上一帧位置。
                Triple(line, latestProgress(), latestFrame())
            }
        }.collect { observation -> observation?.let { focus.observe(it.second, it.third) } }
    }
}
