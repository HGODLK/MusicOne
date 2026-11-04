package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

internal val LocalPlayerAtmosphereFrameClock = staticCompositionLocalOf<PlayerAtmosphereFrameClock?> { null }

/** 流动与换色共用时钟，过渡结束或取消后立即回到低频，不改变真实动画时间。 */
internal class PlayerAtmosphereFrameClock(
    upstream: MonotonicFrameClock,
    configured: () -> AnimationFrameRate,
) : MonotonicFrameClock {
    private var crossfading = false
    private val clock = AnimationFrameClock(upstream, {
        val fps = configured().fps
        when {
            crossfading -> fps
            fps > 0 -> minOf(fps, 8)
            else -> 8
        }
    })

    suspend fun crossfade(block: suspend () -> Unit) {
        crossfading = true
        try { block() } finally { crossfading = false }
    }

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = clock.withFrameNanos(onFrame)
}

/** 常态八帧；切歌时跟随全局 UI 帧率设置，选“跟随屏幕”时不限帧。 */
@Composable
internal fun PlayerAtmosphereAnimationEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val clock = checkNotNull(LocalPlayerAtmosphereFrameClock.current)
    LaunchedEffect(clock, *keys) { withContext(clock, block) }
}
