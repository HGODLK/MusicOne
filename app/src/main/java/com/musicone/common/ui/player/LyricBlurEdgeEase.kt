package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs

private const val LYRIC_BLUR_EDGE_EASE_MILLIS = 600
// 起步和收尾都放缓，亮度变化没有明显的开始和结束。
private val LYRIC_BLUR_EDGE_EASING = CubicBezierEasing(.45f, 0f, .55f, 1f)

/**
 * 底部遮罩上沿的上扩范围由当前句底部决定，切歌或换句时可能一帧跳很远，落在这段高度的文字亮度会突变。
 * 跳变时把差值记成余量并慢慢收回；连续变化不经过这里，开合和控件位移仍然一比一跟随。
 */
internal class LyricBlurEdgeEase {
    private val residual = Animatable(0f)
    private val requests = Channel<Float>(Channel.CONFLATED)
    private var lastTarget: Float? = null
    private var lastShown = 0f
    private var pending: Float? = null

    /** 只在绘制阶段调用。必须每次都读余量，绘制才会订阅后续动画帧。 */
    fun span(target: Float, jumpThreshold: Float): Float {
        val animated = residual.value
        val previous = lastTarget
        lastTarget = target
        if (previous != null && abs(target - previous) > jumpThreshold) {
            val next = lastShown - target
            pending = next
            requests.trySend(next)
        }
        val shown = target + (pending ?: animated)
        lastShown = shown
        return shown
    }

    suspend fun run() {
        requests.receiveAsFlow().collectLatest { start ->
            residual.snapTo(start)
            pending = null
            residual.animateTo(0f, tween(LYRIC_BLUR_EDGE_EASE_MILLIS, easing = LYRIC_BLUR_EDGE_EASING))
        }
    }
}
