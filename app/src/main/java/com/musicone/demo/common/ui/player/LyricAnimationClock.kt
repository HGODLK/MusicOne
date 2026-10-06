package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

internal val LocalLyricAnimationClock = staticCompositionLocalOf<MonotonicFrameClock?> { null }

/** 歌词使用独立屏幕时钟，UI 限制到 30 帧时仍可按歌词设置刷新。 */
@Composable
internal fun LyricAnimationEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val clock = LocalLyricAnimationClock.current
    LaunchedEffect(*keys) { withContext(clock ?: EmptyCoroutineContext, block) }
}
