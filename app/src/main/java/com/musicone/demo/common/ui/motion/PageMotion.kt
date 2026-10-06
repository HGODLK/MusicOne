package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
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
) {
    val progress = Animatable(if (initialOpen) 1f else 0f)
    var phase by mutableStateOf(if (initialOpen) MotionPhase.SHOWN else MotionPhase.HIDDEN)
        private set
    var wantsOpen by mutableStateOf(initialOpen)
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
    var coverKey = "cover"
    var waitForTarget = true
    private var job: Job? = null
    private var dragReleaseVelocity = 0f
    val mounted get() = phase != MotionPhase.HIDDEN
    val moving get() = phase == MotionPhase.MOVING || phase == MotionPhase.DRAGGING
    val value get() = if (phase == MotionPhase.DRAGGING) dragProgress else progress.value
    val hasSharedCover get() = sourceSnapshot[coverKey] != null && targetSnapshot[coverKey] != null

    fun updateHost(bounds: Rect) {
        val resized = hostBounds.width > 0f && hostBounds.size != bounds.size
        hostBounds = bounds
        if (resized && mounted && phase != MotionPhase.SHOWN) {
            job?.cancel()
            job = scope.launch {
                progress.snapTo(if (wantsOpen) 1f else 0f)
                complete()
            }
        }
    }

    fun request(open: Boolean, rebound: Boolean = false, settle: (suspend () -> Unit)? = null) {
        if (open == wantsOpen && phase != MotionPhase.DRAGGING) {
            if (open && phase != MotionPhase.HIDDEN) return
            if (!open && phase != MotionPhase.SHOWN) return
        }
        wantsOpen = open
        val retainedVelocity = progress.velocity
        job?.cancel()
        val fromDrag = phase == MotionPhase.DRAGGING
        if (fromDrag || (!open && phase == MotionPhase.SHOWN)) targetContentHandoff = true
        else if (open && phase == MotionPhase.HIDDEN) targetContentHandoff = false
        val start = value
        val releaseVelocity = dragReleaseVelocity
        dragReleaseVelocity = 0f
        val needsPreparation = phase == MotionPhase.HIDDEN || phase == MotionPhase.PREPARING
        if (open && needsPreparation) phase = MotionPhase.PREPARING
        job = scope.launch {
            if (fromDrag) progress.snapTo(start)
            if (open && needsPreparation && waitForTarget) {
                // 只等待本次布局，不等待网络；未就绪时使用淡入回退。
                withTimeoutOrNull(160L) {
                    var last: Rect? = null
                    var matches = 0
                    while (matches < 2) {
                        withFrameNanos { }
                        val bounds = targets[coverKey]?.bounds
                        matches = if (bounds != null && bounds == last) matches + 1 else 0
                        last = bounds
                    }
                }
            }
            if (!moving) captureAnchors()
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
            } else if (!open && exitAnimation != null) {
                progress.animateTo(end, exitAnimation, initialVelocity = initialVelocity)
            } else if (rebound) {
                progress.animateTo(end, musicSpring(stiffness = 420f), initialVelocity = initialVelocity)
            } else if (open && enterAnimation != null) {
                progress.animateTo(end, enterAnimation, initialVelocity = initialVelocity)
            } else {
                progress.animateTo(end, musicMotion(if (open) enterMillis else exitMillis),
                    initialVelocity = initialVelocity)
            }
            // 附属手势位移也归位后再交回真实控件，进度已到终点不代表封面已归位。
            settling?.join()
            // 保留终点画面一帧，再交回真实控件，避免移除动画层时跳过终点。
            withFrameNanos { }
            complete()
        }
    }

    private fun captureAnchors() {
        sourceSnapshot = sources.filterValues { anchorFits(it.bounds, hostBounds) }
        targetSnapshot = targets.filterValues { anchorFits(it.bounds, hostBounds) }
    }

    private fun complete() {
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
        dragReleaseVelocity = 0f
        phase = MotionPhase.DRAGGING
        return true
    }

    fun dragTo(value: Float) { dragProgress = value.coerceIn(0f, 1f) }

    fun releaseDragWithVelocity(velocity: Float) {
        dragReleaseVelocity = velocity.coerceIn(-1.6f, 1.6f)
    }
}

@Composable
internal fun rememberPageMotion(initialOpen: Boolean = false, enterMillis: Int = 320, exitMillis: Int = 280,
    exitAnimation: AnimationSpec<Float>? = null,
    enterAnimation: AnimationSpec<Float>? = null): PageMotion {
    val scope = rememberCoroutineScope()
    return remember { PageMotion(scope, initialOpen, enterMillis, exitMillis, exitAnimation, enterAnimation) }
}

internal val LocalPlaylistMotion = staticCompositionLocalOf<PageMotion?> { null }
internal val LocalPlayerMotion = staticCompositionLocalOf<PageMotion?> { null }
