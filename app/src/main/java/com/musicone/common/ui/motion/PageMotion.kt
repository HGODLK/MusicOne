package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class MotionPhase { HIDDEN, PREPARING, MOVING, SHOWN, DRAGGING }

internal data class MotionAnchor(
    val bounds: Rect,
    val corner: Float = 0f,
    val markSize: Float = 0f,
    val markX: Float = 1f,
    val markY: Float = -1f,
    val bottomFade: Float = 0f,
    val contentScale: Float = 1f,
    val textSizeSp: Float = 0f,
)

internal class PageMotion(
    private val scope: CoroutineScope,
    initialOpen: Boolean,
    private val enterMillis: Int,
    private val exitMillis: Int,
    private val exitAnimation: AnimationSpec<Float>? = null,
    private val enterAnimation: AnimationSpec<Float>? = null,
    private val dragAnimation: AnimationSpec<Float>? = null,
    private val immediateGestureHandoff: Boolean = false,
    private val sharedAnchorKeys: Set<String> = emptySet(),
) {
    val progress = Animatable(if (initialOpen) 1f else 0f).apply {
        if (dragAnimation != null) updateBounds(0f, 1f)
    }
    var phase by mutableStateOf(if (initialOpen) MotionPhase.SHOWN else MotionPhase.HIDDEN)
        private set
    var wantsOpen by mutableStateOf(initialOpen)
        private set
    var openRequestedAtNanos by mutableLongStateOf(0L)
        private set
    var hostBounds by mutableStateOf(Rect.Zero)
        private set
    val sources = mutableStateMapOf<String, MotionAnchor>()
    val targets = mutableStateMapOf<String, MotionAnchor>()
    var sourceSnapshot by mutableStateOf<Map<String, MotionAnchor>>(emptyMap())
        private set
    var targetSnapshot by mutableStateOf<Map<String, MotionAnchor>>(emptyMap())
        private set
    var dragProgress by mutableFloatStateOf(1f)
        private set
    var targetContentHandoff by mutableStateOf(false)
        private set
    var onHidden: () -> Unit = {}
    var onPreparationAborted: () -> Unit = {}
    var prepareSharedAnchors: ((PageMotion) -> Boolean)? = null
    var beforeShownHandoff: (suspend (PageMotion) -> Unit)? = null
    var coverKey = "cover"
    var waitForTarget = true
    // 音源自建页面只覆盖开场时长，退出继续使用宿主原有曲线。
    var openingDurationOverride: Int? = null
    var openingPreparationOverride: (suspend () -> Unit)? = null
    var openingPreparationStarted: (() -> Unit)? = null
    private var job: Job? = null
    private var dragReleaseVelocity = 0f
    private var dragReleaseTimeNanos: Long? = null
    private var gestureSettling = false
    private var openingDrag = false
    // 准备期积累的手指距离，交给画面逐帧追上，不在一帧内跳过去。
    private var openingDragLag by mutableFloatStateOf(0f)
    val mounted get() = phase != MotionPhase.HIDDEN
    val moving get() = phase == MotionPhase.MOVING || phase == MotionPhase.DRAGGING
    val value get() = if (phase == MotionPhase.DRAGGING) (dragProgress - openingDragLag).coerceAtLeast(0f) else progress.value
    val hasSharedCover get() = sourceSnapshot[coverKey] != null && targetSnapshot[coverKey] != null

    fun updateHost(bounds: Rect) {
        val previous = hostBounds
        val resized = hostBounds.width > 0f && hostBounds.size != bounds.size
        hostBounds = bounds
        if (resized && mounted && phase != MotionPhase.SHOWN) {
            if (sharedAnchorKeys.isNotEmpty()) {
                // 播放页改窗保留进度及速度，快照同步到新坐标，不取消整个行程。
                fun Map<String, MotionAnchor>.resized() = mapValues { (_, anchor) ->
                    anchor.copy(bounds = Rect(
                        bounds.left + (anchor.bounds.left - previous.left) * bounds.width / previous.width,
                        bounds.top + (anchor.bounds.top - previous.top) * bounds.height / previous.height,
                        bounds.left + (anchor.bounds.right - previous.left) * bounds.width / previous.width,
                        bounds.top + (anchor.bounds.bottom - previous.top) * bounds.height / previous.height))
                }
                if (previous.height > 0f && bounds.width > 0f && bounds.height > 0f) {
                    sourceSnapshot = sourceSnapshot.resized()
                    targetSnapshot = targetSnapshot.resized()
                }
                return
            }
            job?.cancel()
            job = scope.launch {
                progress.snapTo(if (wantsOpen) 1f else 0f)
                complete()
            }
        }
    }

    fun request(open: Boolean, rebound: Boolean = false, settle: (suspend () -> Unit)? = null) {
        if (open == wantsOpen && phase != MotionPhase.DRAGGING && !openingDrag) {
            if (open && phase != MotionPhase.HIDDEN) return
            if (!open && phase != MotionPhase.SHOWN) return
        }
        if (open && !wantsOpen) openRequestedAtNanos = System.nanoTime()
        wantsOpen = open
        val retainedVelocity = progress.velocity
        job?.cancel()
        val wasOpeningDrag = openingDrag
        val fromDrag = phase == MotionPhase.DRAGGING || openingDrag
        val preparingDrag = openingDrag && phase == MotionPhase.PREPARING
        openingDrag = false
        val continueGesture = fromDrag || (phase == MotionPhase.MOVING && gestureSettling)
        gestureSettling = continueGesture
        if ((fromDrag && !wasOpeningDrag) || (!open && phase == MotionPhase.SHOWN)) targetContentHandoff = true
        else if (open && phase == MotionPhase.HIDDEN) targetContentHandoff = false
        // 等布局的打开手势此时画面还在起点，从起点带速度出发；不等布局的页面没有可见延迟，直接接手指。
        val start = if (preparingDrag && !waitForTarget) dragProgress else value
        if (openingDragLag > 0f) {
            dragProgress = start
            openingDragLag = 0f
        }
        val releaseVelocity = dragReleaseVelocity
        val releaseTimeNanos = if (fromDrag && immediateGestureHandoff) dragReleaseTimeNanos else null
        dragReleaseVelocity = 0f
        dragReleaseTimeNanos = null
        val needsPreparation = phase == MotionPhase.HIDDEN || phase == MotionPhase.PREPARING
        if (open && phase == MotionPhase.HIDDEN) openingPreparationStarted?.invoke()
        if (open && needsPreparation) phase = MotionPhase.PREPARING
        job = scope.launch(start = if (fromDrag && immediateGestureHandoff)
            CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT) {
            if (preparingDrag && !open) {
                progress.snapTo(0f)
                complete()
                return@launch
            }
            if ((open || preparingDrag) && needsPreparation && waitForTarget &&
                (!fromDrag || openingPreparationOverride != null)) {
                // 只等待本次布局，不等待网络；未就绪时使用淡入回退。
                val preparation = openingPreparationOverride
                if (preparation != null) preparation()
                if (openingPreparationOverride == null && !awaitStableAnchors()) {
                    abortPreparation()
                    return@launch
                }
            }
            if (!moving) captureAnchors()
            if (fromDrag) progress.snapTo(start)
            phase = MotionPhase.MOVING
            val settling = settle?.let { launch { it() } }
            val end = if (open) 1f else 0f
            if (start == end && !fromDrag) {
                progress.snapTo(end)
                complete()
                return@launch
            }
            val initialVelocity = if (fromDrag) releaseVelocity else retainedVelocity
            if (ExperiencePreferences.options.reduceMotion) {
                progress.snapTo(end)
            } else if (continueGesture && dragAnimation != null) {
                // 松手及中途反向共用弹簧，位置和速度连续，不重新进入零斜率补间。
                progress.animateToWithGestureHandoff(end, dragAnimation, initialVelocity, releaseTimeNanos)
            } else if (!open && exitAnimation != null) {
                progress.animateTo(end, exitAnimation, initialVelocity = initialVelocity)
            } else if (rebound) {
                progress.animateTo(end, musicSpring(stiffness = 420f), initialVelocity = initialVelocity)
            } else if (open && openingDurationOverride != null) {
                progress.animateTo(end, musicMotion(openingDurationOverride!!.coerceAtLeast(0)), initialVelocity = initialVelocity)
            } else if (open && enterAnimation != null) {
                progress.animateTo(end, enterAnimation, initialVelocity = initialVelocity)
            } else {
                progress.animateTo(end, musicMotion(if (open) enterMillis else exitMillis),
                    initialVelocity = initialVelocity)
            }
            // 附属手势位移也归位后再交回真实控件，进度已到终点不代表封面已归位。
            settling?.join()
            if (open) beforeShownHandoff?.invoke(this@PageMotion)
            // 保留终点画面一帧，再交回真实控件，避免移除动画层时跳过终点。
            withFrameNanos { }
            complete()
        }
    }

    private suspend fun awaitStableAnchors(): Boolean {
        withTimeoutOrNull(160L) {
            var last: List<Rect?>? = null
            var matches = 0
            while (matches < 2) {
                withFrameNanos { }
                // 只有播放页显式要求共享内容整体就绪，其他页面仍只等待原封面锚点。
                val bounds = if (sharedAnchorKeys.isEmpty()) listOf(targets[coverKey]?.bounds)
                else sharedAnchorKeys.flatMap { key -> listOf(sources[key]?.bounds, targets[key]?.bounds) }
                val ready = prepareSharedAnchors?.invoke(this@PageMotion) != false &&
                    if (sharedAnchorKeys.isEmpty()) bounds.all { it != null } else sharedAnchorsReady()
                matches = if (ready && bounds == last) matches + 1 else 0
                last = bounds
            }
        }
        // 沿用原布局准备预算；未能提交本轮端点时撤销打开，不取旧快照，也不永久遮挡底页。
        return prepareSharedAnchors?.invoke(this) != false &&
            (prepareSharedAnchors == null || sharedAnchorsReady())
    }

    private fun abortPreparation() {
        openingDrag = false
        wantsOpen = false
        complete()
        onPreparationAborted()
    }

    private fun sharedAnchorsReady(): Boolean = sharedAnchorKeys.all { key ->
        sources[key]?.let { validSharedAnchor(key, it) } == true &&
            targets[key]?.let { validSharedAnchor(key, it) } == true
    }

    private fun validSharedAnchor(key: String, anchor: MotionAnchor): Boolean {
        if (anchorFits(anchor.bounds, hostBounds)) return true
        val b = anchor.bounds
        // 空标题或歌手没有字形，但测量完成的空端点仍属于完整交接批次。
        return key in setOf("title", "subtitle") && hostBounds.width > 1f && hostBounds.height > 1f &&
            b.width >= 0f && b.height >= 0f && (b.width <= 1f || b.height <= 1f) &&
            b.left >= hostBounds.left - 1f && b.top >= hostBounds.top - 1f &&
            b.right <= hostBounds.right + 1f && b.bottom <= hostBounds.bottom + 1f
    }

    private fun captureAnchors() {
        sourceSnapshot = sources.filter { (key, anchor) -> validSharedAnchor(key, anchor) }
        targetSnapshot = targets.filter { (key, anchor) -> validSharedAnchor(key, anchor) }
    }

    internal fun replaceCapturedCoverTarget(anchor: MotionAnchor) {
        targetSnapshot = targetSnapshot + (coverKey to anchor)
    }

    /** 飞行途中端点本身在动时，只更新本批次已捕获的目标，不重启整页进度。 */
    internal fun refreshCapturedTargets(keys: Set<String>) {
        var next = targetSnapshot
        keys.forEach { key ->
            val live = targets[key]
            if (live != null && next[key] != null && next[key] != live && validSharedAnchor(key, live))
                next = next + (key to live)
        }
        if (next !== targetSnapshot) targetSnapshot = next
    }

    private fun complete() {
        gestureSettling = false
        openingDragLag = 0f
        phase = if (wantsOpen) MotionPhase.SHOWN else MotionPhase.HIDDEN
        targetContentHandoff = false
        if (!wantsOpen) {
            sourceSnapshot = emptyMap()
            targetSnapshot = emptyMap()
            onHidden()
        }
    }

    fun beginDrag(): Boolean {
        if (!mounted || phase == MotionPhase.PREPARING) return false
        job?.cancel()
        if (!moving) captureAnchors()
        targetContentHandoff = true
        dragProgress = value
        openingDragLag = 0f
        dragReleaseVelocity = 0f
        dragReleaseTimeNanos = null
        phase = MotionPhase.DRAGGING
        return true
    }

    fun beginOpeningDrag(): Boolean {
        if (mounted) return false
        job?.cancel()
        openingPreparationStarted?.invoke()
        openRequestedAtNanos = System.nanoTime()
        wantsOpen = true
        openingDrag = true
        targetContentHandoff = false
        dragProgress = 0f
        dragReleaseVelocity = 0f
        dragReleaseTimeNanos = null
        phase = MotionPhase.PREPARING
        job = scope.launch {
            if (waitForTarget) {
                val preparation = openingPreparationOverride
                if (preparation != null) preparation()
                if (openingPreparationOverride == null && !awaitStableAnchors()) {
                    abortPreparation()
                    return@launch
                }
            }
            // 准备期间累计手指位置，布局就绪后从起点平滑追上，之后恢复一比一跟手。
            captureAnchors()
            val lag = if (waitForTarget && !ExperiencePreferences.options.reduceMotion) dragProgress else 0f
            openingDragLag = lag
            phase = MotionPhase.DRAGGING
            if (lag > 0f) {
                val startNanos = withFrameNanos { it }
                while (openingDragLag > 0f) {
                    withFrameNanos { openingDragLag = openingDragLagRemaining(lag, it - startNanos) }
                }
            }
        }
        return true
    }

    fun dragTo(value: Float) { dragProgress = value.coerceIn(0f, 1f) }

    fun releaseDragWithVelocity(velocity: Float, maxVelocity: Float = 1.6f,
        releaseTimeNanos: Long = System.nanoTime()) {
        dragReleaseVelocity = velocity.coerceIn(-maxVelocity, maxVelocity)
        dragReleaseTimeNanos = releaseTimeNanos
    }
}

internal const val OPENING_DRAG_CATCH_UP_NANOS = 120_000_000L

/** 追赶量先慢后快再慢地归零，起步和结束都与手指速度衔接。 */
internal fun openingDragLagRemaining(lag: Float, elapsedNanos: Long): Float {
    val t = (elapsedNanos.toFloat() / OPENING_DRAG_CATCH_UP_NANOS).coerceIn(0f, 1f)
    return lag * (1f - t * t * (3f - 2f * t))
}

@Composable
internal fun rememberPageMotion(initialOpen: Boolean = false, enterMillis: Int = 320, exitMillis: Int = 280,
    exitAnimation: AnimationSpec<Float>? = null,
    enterAnimation: AnimationSpec<Float>? = null,
    dragAnimation: AnimationSpec<Float>? = null,
    immediateGestureHandoff: Boolean = false,
    sharedAnchorKeys: Set<String> = emptySet()): PageMotion {
    val scope = rememberCoroutineScope()
    return remember { PageMotion(scope, initialOpen, enterMillis, exitMillis, exitAnimation, enterAnimation,
        dragAnimation, immediateGestureHandoff, sharedAnchorKeys) }
}

internal val LocalPlaylistMotion = staticCompositionLocalOf<PageMotion?> { null }
internal val LocalPlayerMotion = staticCompositionLocalOf<PageMotion?> { null }
