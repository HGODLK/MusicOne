package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

internal val LocalLyricAnimationClock = staticCompositionLocalOf<MonotonicFrameClock?> { null }

/** 歌词使用独立屏幕时钟，UI 限制到 30 帧时仍可按歌词设置刷新。 */
@Composable
internal fun LyricAnimationEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val clock = LocalLyricAnimationClock.current
    LaunchedEffect(*keys) { withContext(clock ?: EmptyCoroutineContext, block) }
}

/** 只转发现有开合动画的实际帧，不增加循环、不改变上游 UI 节拍或歌词播放帧率。 */
internal suspend fun <T> withLyricMotionFrames(driver: LyricFrameDriver?, block: suspend CoroutineScope.() -> T): T {
    val upstream = currentCoroutineContext()[MonotonicFrameClock]
    return if (driver == null || upstream == null) coroutineScope(block)
    else withContext(AnimationFrameClock(upstream, { 0 }, onFrameAccepted = driver::onFrame), block)
}
