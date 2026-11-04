package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect

/** 点击焦点覆盖句首预留区间，逐字时间仍由真实播放时钟决定。 */
internal class LyricTapFocus {
    var line by mutableStateOf<Int?>(null)
        private set
    private var startMs = 0L
    private var seekMs = 0L
    private var originMs: Long? = null
    private var seekRevision: Long? = null

    fun request(index: Int, timeMs: Long, seekPositionMs: Long, fromPositionMs: Long? = null) {
        startMs = timeMs
        seekMs = seekPositionMs
        originMs = fromPositionMs?.takeIf { it != seekPositionMs }
        line = index
    }

    fun observe(positionMs: Long, framePositionMs: Long = positionMs) {
        originMs?.let { origin ->
            // 等待本次跳转真正改变播放采样，旧进度不能提前撤掉刚点击的焦点。
            val committed = if (seekMs > origin) positionMs >= seekMs && framePositionMs >= seekMs
                else positionMs < origin && framePositionMs < origin
            if (!committed) return
            originMs = null
        }
        // 进入句首后恢复跟随；其他定位跳出预留区间时也交还控制权。
        if ((positionMs >= startMs && framePositionMs >= startMs) || positionMs < seekMs - 1L) clear()
    }

    fun progressSeek(revision: Long?) {
        if (seekRevision != revision) clear()
        seekRevision = revision
    }
    fun clear() { line = null; originMs = null }
}

@Composable
internal fun LyricTapFocusEffect(
    focus: LyricTapFocus,
    frameProgressMs: () -> Long,
    latestProgressMs: () -> Long,
    interrupted: Boolean,
    seekRevision: Long?,
    paused: Boolean = false,
) {
    val latestFrame by rememberUpdatedState(frameProgressMs)
    val latestProgress by rememberUpdatedState(latestProgressMs)
    LyricAnimationEffect(focus, interrupted, seekRevision, paused) {
        focus.progressSeek(seekRevision)
        if (interrupted) { focus.clear(); return@LyricAnimationEffect }
        if (paused) return@LyricAnimationEffect
        snapshotFlow {
            focus.line?.let { line ->
                // 正式帧时钟负责唤醒，实时采样避免倒退 seek 时误用上一帧位置。
                Triple(line, latestProgress(), latestFrame())
            }
        }.collect { observation -> observation?.let { focus.observe(it.second, it.third) } }
    }
}
