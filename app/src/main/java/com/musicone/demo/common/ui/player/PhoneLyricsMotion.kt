package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 歌词和页眉保存各自的实际位置与速度，反向操作只更换目标。 */
@Stable
internal class PhoneLyricsMotion(initiallyVisible: Boolean) {
    val lyrics = Animatable(if (initiallyVisible) 1f else 0f, visibilityThreshold = .0005f)
    val header = Animatable(if (initiallyVisible) 1f else 0f, visibilityThreshold = .0005f)
    private var lyricsVelocity = 0f
    private var headerVelocity = 0f
    var contactProgress = .4f
    var dragTravelPx = 1f
    private var animationJob: Job? = null
    private var dragOrigin = LyricsDragPosition(0f, 0f)
    private var dragged by mutableStateOf<LyricsDragPosition?>(null)
    var dragging by mutableStateOf(false)
        private set
    var gestureRevision by mutableIntStateOf(0)
        private set
    var openingAlignment by mutableStateOf<CompletableDeferred<Unit>?>(null)
        private set
    val lyricsProgress get() = dragged?.lyrics ?: lyrics.value
    val headerProgress get() = dragged?.header ?: header.value

    fun beginDrag() {
        dragOrigin = LyricsDragPosition(lyricsProgress, headerProgress)
        animationJob?.cancel()
        lyricsVelocity = 0f
        headerVelocity = 0f
        dragged = dragOrigin
        dragging = true
    }

    fun dragTo(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val h = lyricsDragHeaderProgress(p, dragOrigin.lyrics, dragOrigin.header, contactProgress)
        dragged = LyricsDragPosition(p, h)
    }

    fun finishDrag() {
        dragging = false
        gestureRevision++
    }

    suspend fun moveTo(visible: Boolean, exitAlignment: Deferred<Unit>? = null,
        prepareOpening: Boolean = false) = coroutineScope {
        animationJob = currentCoroutineContext()[Job]
        exitAlignment?.await()
        // 完全隐藏时先准备当前歌词；中途反向和手势释放保留实际位置。
        if (prepareOpening && visible && lyricsProgress == 0f) {
            val ready = CompletableDeferred<Unit>()
            openingAlignment = ready
            try {
                ready.await()
            } finally {
                if (openingAlignment === ready) openingAlignment = null
            }
        }
        dragged?.let {
            lyrics.snapTo(it.lyrics)
            header.snapTo(it.header)
            dragged = null
        }
        moveToTarget(visible)
    }

    private suspend fun moveToTarget(visible: Boolean) = coroutineScope {
        val target = if (visible) 1f else 0f
        launch {
            lyrics.animateTo(
                target,
                musicSpring(stiffness = if (visible) 210f else 300f,
                    visibilityThreshold = .0005f),
                initialVelocity = lyricsVelocity,
            ) { lyricsVelocity = velocity }
            lyricsVelocity = 0f
        }
        launch {
            // 仅从完整封面页打开时等待接触；中途反向立即接续，不重播等待阶段。
            if (visible && header.value <= .0005f && headerVelocity == 0f) {
                snapshotFlow { lyrics.value }.first { it >= contactProgress }
            }
            header.animateTo(
                target,
                musicSpring(stiffness = 240f,
                    visibilityThreshold = .0005f),
                initialVelocity = headerVelocity,
            ) { headerVelocity = velocity }
            headerVelocity = 0f
        }
    }
}

@Composable
internal fun rememberPhoneLyricsMotion(
    visible: Boolean,
    exitAlignment: Deferred<Unit>? = null,
): PhoneLyricsMotion {
    val motion = remember { PhoneLyricsMotion(visible) }
    LaunchedEffect(visible, exitAlignment, motion.gestureRevision) {
        // 开关请求和对应的列表对齐对象使用同一任务；反向时会一起取消。
        if (!motion.dragging) motion.moveTo(visible, exitAlignment, prepareOpening = true)
    }
    return motion
}

private data class LyricsDragPosition(val lyrics: Float, val header: Float)

/** 手势从当前画面出发，两个端点精确归位，中途原路返回不会产生位置跳变。 */
internal fun lyricsDragHeaderProgress(progress: Float, start: Float, header: Float, contact: Float): Float {
    fun curve(value: Float) = ((value - contact) / (1f - contact).coerceAtLeast(.001f)).coerceIn(0f, 1f)
    val initial = curve(start)
    return when {
        progress == start -> header
        progress < start && initial > .0005f -> header * curve(progress) / initial
        progress < start -> header * progress / start.coerceAtLeast(.001f)
        else -> header + (1f - header) * (curve(progress) - initial) / (1f - initial).coerceAtLeast(.001f)
    }.coerceIn(0f, 1f)
}

internal fun lyricsContactProgress(height: Float, headerHeight: Float, informationBottom: Float): Float =
    ((height - informationBottom) / (height - headerHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
