package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 歌词和页眉保存各自的实际位置与速度，反向操作只更换目标。 */
@Stable
internal class PhoneLyricsMotion(initiallyVisible: Boolean, private val scope: CoroutineScope? = null) {
    val spatialTravel = PhoneLyricsSpatialTravel()
    val lyrics = Animatable(if (initiallyVisible) 1f else 0f, visibilityThreshold = .0005f)
    val header = Animatable(if (initiallyVisible) 1f else 0f, visibilityThreshold = .0005f)
    private var lyricsVelocity = 0f
    private var headerVelocity = 0f
    private var dragReleaseTimeNanos: Long? = null
    var dragTravelPx = 1f
    var controlsSpacePx = 0f
    private var animationJob: Job? = null
    private var directGestureRevision = -1
    private var directGestureTarget = false
    private var dragOrigin = LyricsDragPosition(0f, 0f)
    private var dragged by mutableStateOf<LyricsDragPosition?>(null)
    var dragging by mutableStateOf(false)
        private set
    var draggingTowardClose by mutableStateOf(false)
        private set
    private var previousDragProgress = 0f
    var gestureRevision by mutableIntStateOf(0)
        private set
    var openingAlignment by mutableStateOf<CompletableDeferred<Unit>?>(null)
        private set
    val lyricsProgress get() = dragged?.lyrics ?: lyrics.value
    val headerProgress get() = dragged?.header ?: header.value
    val visibleLyricsVelocity get() = lyricsVelocity
    val visibleHeaderVelocity get() = headerVelocity
    // 入场对齐跟随窗口实际行程，页眉占位期间不能继续反向补滚列表。
    val entranceProgress get() = phoneLyricsEntranceProgress(windowOffsetPx, dragTravelPx)
    val windowOffsetPx get() = phoneLyricsSpatialWindowOffset(dragTravelPx, lyricsProgress,
        headerProgress, if (spatialTravel.compactHeightPx > 0f) {
            spatialTravel.compactHeightPx + controlsSpacePx
        } else 0f, spatialTravel.softnessPx)
    val windowVelocityPxPerSecond: Float get() {
        val interval = 1f / 120f
        val compact = if (spatialTravel.compactHeightPx > 0f) spatialTravel.compactHeightPx + controlsSpacePx else 0f
        val next = phoneLyricsSpatialWindowOffsetRaw(dragTravelPx,
            lyricsProgress + visibleLyricsVelocity * interval,
            headerProgress + visibleHeaderVelocity * interval, compact, spatialTravel.softnessPx)
        val current = phoneLyricsSpatialWindowOffsetRaw(dragTravelPx, lyricsProgress,
            headerProgress, compact, spatialTravel.softnessPx)
        return (next - current) / interval
    }

    /** 页面已不可见时直接复位，避免紧接着重新打开时续播隐藏中的歌词收起动画。 */
    suspend fun resetWhileHidden() {
        animationJob?.cancel()
        animationJob = null
        openingAlignment = null
        dragged = null
        dragging = false
        draggingTowardClose = false
        directGestureRevision = -1
        dragReleaseTimeNanos = null
        lyricsVelocity = 0f
        headerVelocity = 0f
        lyrics.snapTo(0f)
        header.snapTo(0f)
    }

    private fun captureSpatialTravelAtRest() {
        if ((lyricsProgress == 0f && headerProgress == 0f) ||
            (lyricsProgress == 1f && headerProgress == 1f)) spatialTravel.capture()
    }

    fun beginDrag() {
        directGestureRevision = -1
        captureSpatialTravelAtRest()
        dragOrigin = LyricsDragPosition(lyricsProgress, headerProgress)
        animationJob?.cancel()
        dragReleaseTimeNanos = null
        lyricsVelocity = 0f
        headerVelocity = 0f
        dragged = dragOrigin
        dragging = true
        previousDragProgress = dragOrigin.lyrics
        draggingTowardClose = dragOrigin.lyrics >= 1f
    }

    fun dragTo(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (kotlin.math.abs(p - previousDragProgress) > .012f) {
            draggingTowardClose = p < previousDragProgress
            previousDragProgress = p
        }
        val h = lyricsDragHeaderProgress(p, dragOrigin.lyrics, dragOrigin.header)
        dragged = LyricsDragPosition(p, h)
    }

    fun finishDrag(velocityY: Float = 0f, releaseTimeNanos: Long = System.nanoTime()) {
        val travel = dragTravelPx.coerceAtLeast(1f)
        val p = lyricsProgress
        val vLyrics = (-velocityY / travel).coerceIn(-6f, 6f)
        lyricsVelocity = vLyrics
        headerVelocity = lyricsDragHeaderVelocity(p, dragOrigin.lyrics, dragOrigin.header, vLyrics)
        dragging = false
        draggingTowardClose = false
        dragReleaseTimeNanos = releaseTimeNanos
        gestureRevision++
    }

    /** 抬手事件内交接位置、速度和零时刻；页面状态通知不再重复启动同一次收尾。 */
    fun settleDrag(visible: Boolean, velocityY: Float, releaseTimeNanos: Long = System.nanoTime()) {
        finishDrag(velocityY, releaseTimeNanos)
        val animationScope = scope ?: return
        directGestureRevision = gestureRevision
        directGestureTarget = visible
        animationJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            moveTo(visible, fromGesture = true)
        }
    }

    fun settlingGesture(visible: Boolean): Boolean =
        directGestureRevision == gestureRevision && directGestureTarget == visible

    suspend fun moveTo(visible: Boolean, exitAlignment: Deferred<Unit>? = null,
        prepareOpening: Boolean = false, fromGesture: Boolean = false) = coroutineScope {
        val currentJob = currentCoroutineContext()[Job]
        if (animationJob !== currentJob) animationJob?.cancel()
        animationJob = currentJob
        if (!fromGesture) directGestureRevision = -1
        // 只有从完全关闭态启动时才等待退场就绪，中途手势释放不能被旧对齐阻塞。
        if (lyricsProgress == 0f) exitAlignment?.await()
        // 完全隐藏时先准备当前歌词；中途反向和手势释放保留实际位置。
        if (prepareOpening && visible && lyricsProgress == 0f && dragged == null) {
            val ready = CompletableDeferred<Unit>()
            openingAlignment = ready
            try {
                ready.await()
            } finally {
                if (openingAlignment === ready) openingAlignment = null
            }
        }
        captureSpatialTravelAtRest()
        val releaseTimeNanos = if (dragged != null) dragReleaseTimeNanos else null
        dragReleaseTimeNanos = null
        dragged?.let {
            lyrics.snapTo(it.lyrics)
            header.snapTo(it.header)
            dragged = null
        }
        moveToTarget(visible, releaseTimeNanos)
    }

    private suspend fun moveToTarget(visible: Boolean, releaseTimeNanos: Long?) = coroutineScope {
        val target = if (visible) 1f else 0f
        launch {
            lyrics.animateToWithGestureHandoff(
                target,
                musicSpring(stiffness = if (visible) 210f else 300f,
                    visibilityThreshold = .0005f),
                initialVelocity = lyricsVelocity,
                releaseTimeNanos = releaseTimeNanos,
            ) { lyricsVelocity = velocity }
            lyricsVelocity = 0f
        }
        launch {
            // 完全关闭与中途重开都从实际位置、速度立即让位，不等待歌词接触封面。
            header.animateToWithGestureHandoff(
                target,
                musicSpring(stiffness = 240f,
                    visibilityThreshold = .0005f),
                initialVelocity = headerVelocity,
                releaseTimeNanos = releaseTimeNanos,
            ) { headerVelocity = velocity }
            headerVelocity = 0f
        }
    }
}

@Composable
internal fun rememberPhoneLyricsMotion(
    visible: Boolean,
    exitAlignment: Deferred<Unit>? = null,
    pageVisible: Boolean = true,
    prepareOpening: Boolean = true,
): PhoneLyricsMotion {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val retained = LocalRetainedPhoneLyricsMotion.current
    val motion = remember(retained) { retained ?: PhoneLyricsMotion(visible, scope) }
    LaunchedEffect(visible, exitAlignment, motion.gestureRevision, pageVisible, prepareOpening) {
        // 开关请求和对应的列表对齐对象使用同一任务；反向时会一起取消。
        if (!pageVisible) motion.resetWhileHidden()
        else if (!motion.dragging && !motion.settlingGesture(visible))
            motion.moveTo(visible, exitAlignment, prepareOpening = prepareOpening)
    }
    return motion
}

private data class LyricsDragPosition(val lyrics: Float, val header: Float)

/** 手势从当前画面出发，两个端点精确归位，中途原路返回不会产生位置跳变。 */
internal fun lyricsDragHeaderProgress(progress: Float, start: Float, header: Float): Float = when {
    progress == start -> header
    progress < start -> header * progress / start
    else -> header + (1f - header) * (progress - start) / (1f - start)
}.coerceIn(0f, 1f)

/** 松手速度取自同一段手势映射，端点和反向起点也保留正确的导数。 */
internal fun lyricsDragHeaderVelocity(progress: Float, start: Float, header: Float, velocity: Float): Float {
    val closing = progress < start || progress == start && velocity < 0f
    val slope = if (closing) {
        if (start > 0f) header / start else 0f
    } else {
        if (start < 1f) (1f - header) / (1f - start) else 0f
    }
    return slope * velocity
}

internal fun phoneLyricsEntranceProgress(offsetPx: Float, travelPx: Float): Float =
    (1f - offsetPx / travelPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
