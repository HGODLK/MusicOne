package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull

/** 命中缓存时立即完成；慢封面继续后台加载，不能阻塞整批内容的发布。 */
internal suspend fun preloadQqArtwork(
    urls: List<String?>,
    scope: CoroutineScope,
    waitLimitMs: Long = QQ_ARTWORK_READY_WAIT_MS,
) {
    val pending = urls.filterNotNull().filter(String::isNotBlank).distinct()
        .filter { ArtworkRepository.peek(it) == null }
        .map { url ->
            scope.async(Dispatchers.IO) { runCatching { ArtworkRepository.load(url) }.getOrNull() }
        }
    if (pending.isEmpty()) return
    withTimeoutOrNull(waitLimitMs) { pending.awaitAll() }
}

/** 刷新退场由一条全局曲线驱动，完成后才允许发布新一代，兼容系统动画缩放。 */
@Composable
internal fun rememberQqFeedExitProgress(feed: QqMusicFeedContent, onExited: () -> Unit): () -> Float {
    val exit = remember { Animatable(0f) }
    val latestOnExited by rememberUpdatedState(onExited)
    LaunchedEffect(feed.refreshing, feed.generation) {
        if (feed.refreshing) {
            exit.animateTo(1f, musicMotion(320))
            latestOnExited()
        } else exit.snapTo(0f)
    }
    return { exit.value }
}

/** 每一代的新卡从下方入场；刷新期间旧卡统一上移淡出并停止响应点击。 */
@Composable
internal fun QqMusicFeedCardMotion(refreshing: Boolean, exitProgress: () -> Float,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var appeared by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (appeared) 1f else 0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, musicMotion(320))
        appeared = true
    }
    Box(modifier.graphicsLayer {
        val value = progress.value
        alpha = (value * (1f - exitProgress())).coerceIn(0f, 1f)
        translationY = 28.dp.toPx() * (1f - value - exitProgress())
    }.then(if (refreshing) Modifier.clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } }
        } else Modifier)) { content() }
}

private const val QQ_ARTWORK_READY_WAIT_MS = 300L
