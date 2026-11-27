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
    /** 来源只露出一部分时的可见块（根坐标），只用于判断端点是否可用；bounds 此时是完整位置，可以超出屏幕。null 表示完整可见。 */
    val visible: Rect? = null,
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
    /** 整页向下滑出屏幕的位移（0～1，按页面高度），只用于没有共享端点可飞回的收起。 */
    val slideOut = Animatable(0f)
    /** 页面实际盖住底页的程度：开合进度扣掉滑出部分，给需要随之显隐的底页元素使用。 */
    val coverage get() = value * (1f - slideOut.value)
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
                // 播放页窗口尺寸变化时保留动画进度和速度，按比例缩放锚点快照，不中断当前动画。
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
        // 需要等布局就绪的打开手势此时画面还停在起点，从起点带速度起飞；不用等布局的页面直接从当前手指位置接续。
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
                // 松手和中途反向都用同一套弹簧动画，保证位置和速度连续，避免切回初速度为 0 的补间造成卡顿。
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
            // 等附加的手势位移（如封面归位）也结束后再切回真实页面，进度到终点不代表封面已经归位。
            settling?.join()
            if (open) beforeShownHandoff?.invoke(this@PageMotion)
            // 保留终点画面一帧，再交回真实控件，避免移除动画层时跳过终点。
            withFrameNanos { }
            complete()
        }
    }

    /**
     * 不经过共享飞行，整页滑出屏幕后直接切到隐藏；用于清空队列后迷你条已不在、没有落点的收起。
     * 页面在屏幕外才切换状态，切换本身不可见。
     */
    suspend fun slideOutAndHide(animation: AnimationSpec<Float>) {
        job?.cancel()
        try {
            if (ExperiencePreferences.options.reduceMotion) slideOut.snapTo(1f) else slideOut.animateTo(1f, animation)
            wantsOpen = false
            openingDrag = false
            progress.snapTo(0f)
            complete()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { slideOut.snapTo(0f) }
        }
    }

    private suspend fun awaitStableAnchors(): Boolean {
        withTimeoutOrNull(160L) {
            var last: List<Rect?>? = null
            var matches = 0
            while (matches < 2) {
                withFrameNanos { }
                // 只有播放页要求所有共享元素都就绪，其他页面只等封面锚点即可。
                val bounds = if (sharedAnchorKeys.isEmpty()) listOf(targets[coverKey]?.bounds)
                else sharedAnchorKeys.flatMap { key -> listOf(sources[key]?.bounds, targets[key]?.bounds) }
                val ready = prepareSharedAnchors?.invoke(this@PageMotion) != false &&
                    if (sharedAnchorKeys.isEmpty()) bounds.all { it != null } else sharedAnchorsReady()
                matches = if (ready && bounds == last) matches + 1 else 0
                last = bounds
            }
        }
        // 超时仍未拿到本轮所有共享锚点就取消打开，避免复用旧快照或一直盖住底页。
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
        anchor.visible?.let { return partialAnchorUsable(it, hostBounds) }
        if (anchorFits(anchor.bounds, hostBounds)) return true
        val b = anchor.bounds
        // 标题或歌手为空时宽高可能为 0，只要已在宿主范围内完成测量，也算有效锚点。
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

    /** 飞行途中目标位置有变化时，只刷新已捕获的目标锚点，不重置整页动画进度。 */
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
