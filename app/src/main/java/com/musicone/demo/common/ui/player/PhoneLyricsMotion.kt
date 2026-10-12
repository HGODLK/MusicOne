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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
    var contactProgress = .4f
    var dragTravelPx = 1f
    var controlsSpacePx = 0f
    var openingStage: PhoneLyricsOpeningStage? = null
    private var openingFront: PhoneLyricsOpeningFront? = null
    private var openingTrack: Pair<MusicSource, String>? = null

    fun expectOpeningTrack(source: MusicSource, id: String) {
        val identity = source to id
        if (openingTrack != identity) { openingTrack = identity; openingFront = null }
    }

    fun recordOpeningFront(source: MusicSource, id: String, front: PhoneLyricsOpeningFront) {
        if (openingTrack == (source to id)) openingFront = front
    }
    private var animationJob: Job? = null
    private var directGestureRevision = -1
    private var directGestureTarget = false
    private var dragOrigin = LyricsDragPosition(0f, 0f)
    private var dragContact = .4f
    private var opening by mutableStateOf<PhoneLyricsOpeningHandoff?>(null)
    private var dragOpening: PhoneLyricsOpeningHandoff? = null
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
    val headerProgress get() = dragged?.header ?: opening?.header(lyricsProgress) ?: header.value
    val visibleLyricsVelocity get() = lyricsVelocity
    val visibleHeaderVelocity get() = opening?.let { it.headerSpeed(lyricsProgress) * visibleLyricsVelocity } ?: headerVelocity
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

    private fun captureSpatialTravelAtRest() {
        if ((lyricsProgress == 0f && headerProgress == 0f) ||
            (lyricsProgress == 1f && headerProgress == 1f)) spatialTravel.capture()
    }

    fun beginDrag() {
        directGestureRevision = -1
        captureSpatialTravelAtRest()
        dragOrigin = LyricsDragPosition(lyricsProgress, headerProgress)
        dragContact = contactProgress
        dragOpening = if (dragOrigin.lyrics == 0f && dragOrigin.header == 0f) newOpening() else null
        animationJob?.cancel()
        dragReleaseTimeNanos = null
        lyricsVelocity = 0f
        headerVelocity = 0f
        dragged = dragOrigin
        opening = null
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
        val h = dragOpening?.header(p) ?: lyricsDragHeaderProgress(p, dragOrigin.lyrics, dragOrigin.header, dragContact)
        dragged = LyricsDragPosition(p, h)
    }

    fun finishDrag(velocityY: Float = 0f, releaseTimeNanos: Long = System.nanoTime()) {
        val travel = dragTravelPx.coerceAtLeast(1f)
        val p = lyricsProgress
        val vLyrics = (-velocityY / travel).coerceIn(-6f, 6f)
        lyricsVelocity = vLyrics
        val path = dragOpening
        headerVelocity = if (path != null) {
            path.headerSpeed(p) * vLyrics
        } else {
            val contact = dragContact
            val start = dragOrigin
            val delta = .005f
            val h1 = lyricsDragHeaderProgress((p + delta).coerceIn(0f, 1f), start.lyrics, start.header, contact)
            val h0 = lyricsDragHeaderProgress((p - delta).coerceIn(0f, 1f), start.lyrics, start.header, contact)
            ((h1 - h0) / .01f).coerceIn(0f, 3f) * vLyrics
        }
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
        if (visible && (dragOpening != null || lyricsProgress == 0f && headerProgress == 0f)) {
            val path = dragOpening ?: newOpening()
            if (path != null) {
                val input = dragged?.lyrics ?: 0f
                lyrics.snapTo(input)
                opening = path
                dragged = null
                dragOpening = null
                lyrics.animateToWithGestureHandoff(1f, musicSpring(stiffness = 210f, visibilityThreshold = .0005f),
                    initialVelocity = lyricsVelocity, releaseTimeNanos = releaseTimeNanos) { lyricsVelocity = velocity }
                header.snapTo(1f)
                opening = null
                lyricsVelocity = 0f
                headerVelocity = 0f
                return@coroutineScope
            }
        }
        opening?.let {
            val p = lyricsProgress
            val h = headerProgress
            val pv = visibleLyricsVelocity
            val hv = visibleHeaderVelocity
            lyrics.snapTo(p)
            header.snapTo(h)
            opening = null
            lyricsVelocity = pv
            headerVelocity = hv
        }
        dragOpening = null
        val targetContact = if (dragged != null) dragContact else contactProgress
        dragged?.let {
            lyrics.snapTo(it.lyrics)
            header.snapTo(it.header)
            dragged = null
        }
        moveToTarget(visible, targetContact, releaseTimeNanos)
    }

    private fun newOpening(): PhoneLyricsOpeningHandoff? = spatialTravel.compactHeightPx.takeIf { it > 0f }?.let {
        val geometry = openingStage?.let { stage -> openingFront?.let { front -> phoneLyricsOpeningGeometry(stage, front) } }
        PhoneLyricsOpeningHandoff(dragTravelPx, it + controlsSpacePx, contactProgress, geometry)
    }

    private suspend fun moveToTarget(visible: Boolean, contact: Float, releaseTimeNanos: Long?) = coroutineScope {
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
            var headerReleaseTimeNanos = releaseTimeNanos
            // 仅从完整封面页打开时等待接触；中途反向立即接续，不重播等待阶段。
            if (visible && header.value <= .0005f && headerVelocity == 0f) {
                snapshotFlow { lyrics.value }.first { it >= contact }
                headerReleaseTimeNanos = null
            }
            header.animateToWithGestureHandoff(
                target,
                musicSpring(stiffness = 240f,
                    visibilityThreshold = .0005f),
                initialVelocity = headerVelocity,
                releaseTimeNanos = headerReleaseTimeNanos,
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val motion = remember { PhoneLyricsMotion(visible, scope) }
    LaunchedEffect(visible, exitAlignment, motion.gestureRevision) {
        // 开关请求和对应的列表对齐对象使用同一任务；反向时会一起取消。
        if (!motion.dragging && !motion.settlingGesture(visible))
            motion.moveTo(visible, exitAlignment, prepareOpening = true)
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

internal fun phoneLyricsEntranceProgress(offsetPx: Float, travelPx: Float): Float =
    (1f - offsetPx / travelPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
