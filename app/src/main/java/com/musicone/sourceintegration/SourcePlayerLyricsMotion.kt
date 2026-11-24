package com.musicone.sourceintegration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.musicone.*
import com.musicone.source.sdk.SourcePlayerLyricsMotion
import com.musicone.source.sdk.SourcePlayerLyricsStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

/**
 * 音源自建页的歌词开合，直接复用原生 PhoneLyricsMotion：竖屏为歌词、页眉两条弹簧，
 * 歌词前两行碰到页眉前先在底部软停；打开前等列表对齐当前行，超时改为淡入；关闭后在隐藏态重新对齐。
 * 横屏/双栏为单条弹簧，页眉直接到位。动画跑在宿主作用域，包页面通过快照读取进度。
 */
internal class SourcePlayerLyricsController(scope: CoroutineScope) {
    val motion = PhoneLyricsMotion(false, scope)
    var requested by mutableStateOf(false)
        private set
    // 关闭时新建，列表在隐藏态完成重新对齐后兑现；打开期间为空。
    var exitAlignment by mutableStateOf<CompletableDeferred<Unit>?>(CompletableDeferred())
        private set
    var style by mutableStateOf(SourcePlayerLyricsStyle.STACKED)
    private var travelPx by mutableFloatStateOf(1f)
    private var panels by mutableIntStateOf(0)
    private var displacement: PlayerGestureDisplacement? = null
    private var dragInitial = 0f
    private var dragDistance = 0f
    private var dragOriginal = false

    val panelAttached get() = panels > 0
    val moving get() = motion.dragging || motion.lyricsProgress.let { it > 0f && it < 1f } ||
        motion.headerProgress.let { it > 0f && it < 1f }

    fun request(open: Boolean) {
        if (requested == open) return
        requested = open
        exitAlignment = if (open) null else CompletableDeferred()
    }

    fun dragStart(): Boolean {
        if (displacement != null || motion.dragging) return false
        dragInitial = motion.lyricsProgress
        dragDistance = 0f
        dragOriginal = requested
        displacement = PlayerGestureDisplacement(dragOriginal)
        motion.beginDrag()
        return true
    }

    fun drag(deltaPx: Float) {
        val current = displacement ?: return
        current.move(deltaPx)
        dragDistance += deltaPx
        motion.dragTo(dragInitial + dragDistance / motion.dragTravelPx.coerceAtLeast(1f))
    }

    /** 与原生上滑封面、下拉页眉同一判定；先交接位置和速度，再提交目标，避免页面状态重复启动收尾。 */
    fun dragEnd(velocityPxPerSecond: Float, density: Float) {
        val current = displacement ?: return
        displacement = null
        val target = lyricsDragTarget(motion.lyricsProgress, -velocityPxPerSecond / density,
            -current.distancePx / density, dragOriginal, completed = true, current.reverseDistancePx / density)
        motion.settleDrag(target, -velocityPxPerSecond)
        request(target)
    }

    fun dragCancel() {
        if (displacement == null) return
        displacement = null
        val target = lyricsDragTarget(motion.lyricsProgress, 0f, original = dragOriginal, completed = false)
        motion.settleDrag(target, 0f)
        request(target)
    }

    /** 宿主侧驱动：包页面未挂载面板时也能按 setOpened 推进页眉进度，但不等待不存在的列表对齐。 */
    @Composable
    fun Drive(track: MusicTrack?, loadState: LyricLoadState) {
        val directOpening = loadState == LyricLoadState.LOADING && track?.lyrics.isNullOrEmpty()
        LaunchedEffect(requested, exitAlignment, motion.gestureRevision, style, panelAttached) {
            if (motion.dragging || motion.settlingGesture(requested)) return@LaunchedEffect
            if (style == SourcePlayerLyricsStyle.SIDE) motion.moveLandscape(requested, travelPx)
            else motion.moveTo(requested, exitAlignment.takeIf { panelAttached },
                prepareOpening = panelAttached && !directOpening)
        }
    }

    private fun windowOffset(): Float = if (style == SourcePlayerLyricsStyle.SIDE)
        (1f - motion.lyricsProgress) * travelPx else motion.windowOffsetPx

    private fun windowVelocity(): Float = if (style == SourcePlayerLyricsStyle.SIDE)
        -motion.visibleLyricsVelocity * travelPx else motion.windowVelocityPxPerSecond

    /** 运行在包页面的组合中：包页面根即页面坐标，行程按区域顶端到页面底边计算，关闭端点完全在页面外。 */
    @Composable
    fun Panel(style: SourcePlayerLyricsStyle, modifier: Modifier, track: MusicTrack, loadState: LyricLoadState,
        playback: MusicPlaybackRuntime, interactive: Boolean, direction: TrackTransitionDirection) {
        SideEffect { this.style = style }
        DisposableEffect(this) {
            panels++
            onDispose { panels-- }
        }
        val density = LocalDensity.current
        val stacked = style == SourcePlayerLyricsStyle.STACKED
        val recorder = remember(density) {
            LyricSpatialLayoutRecorder(motion.spatialTravel, density, PlayerLayoutMode.COMPACT_SINGLE)
        }
        val boundary = remember(stacked) {
            if (stacked) LyricBoundaryWindow(0f, offset = ::windowOffset, velocity = ::windowVelocity)
            else LyricBoundaryWindow(topBuffer = 0f, offset = { 0f }, partialTop = true)
        }
        val progress = motion.lyricsProgress
        val closed = !requested && progress <= 0f
        Box(modifier.onGloballyPositioned {
            val pageHeight = it.findRootCoordinates().size.height.toFloat()
            val top = it.positionInRoot().y
            val travel = (pageHeight - top).coerceAtLeast(1f)
            motion.dragTravelPx = travel
            travelPx = travel
            // 区域下方通常是包的控件；歌词前两行软停在控件上沿。
            motion.controlsSpacePx = (pageHeight - top - it.size.height).coerceAtLeast(0f)
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = windowOffset()
                alpha = if (!requested && motion.lyricsProgress <= 0f) 0f else motion.entranceAlpha.value
            }) {
                CompositionLocalProvider(LocalPlayerVisible provides interactive,
                    LocalRetainedPhoneLyricsMotion provides motion,
                    LocalPlayerLyricsMoving provides moving) {
                    PlayerSyncedLyricsContent(track, loadState, playback,
                        Modifier.fillMaxSize().then(if (closed) Modifier.clearAndSetSemantics { } else Modifier),
                        active = interactive && !closed,
                        followPlayback = interactive && requested && !motion.dragging,
                        animateTrackChange = interactive && !motion.dragging,
                        // 手势展开时尚未提交打开，不能被关闭态的旧对齐请求挡住。
                        exitAlignment = if (motion.dragging && !requested) null else exitAlignment,
                        openingAlignment = motion.openingAlignment,
                        prepareWhileHidden = interactive && closed,
                        revealTopLineBeforeEntrance = true,
                        limitTransitionHistory = true,
                        exitProgress = { 1f - motion.lyricsProgress },
                        entranceProgress = if (stacked) ({ motion.entranceProgress }) else ({ motion.lyricsProgress }),
                        lyricsGestureHeld = motion.dragging,
                        closingLyricsDrag = motion.draggingTowardClose,
                        boundaryWindow = boundary,
                        trackTransitionDirection = direction,
                        onWindowLayout = if (stacked) recorder::record else null)
                }
            }
        }
    }
}

/** 对包暴露的歌词开合对象：读取进度不做交互校验，发起开合与起手须在页面可操作时调用；check 为 false 表示页面实例已失效，静默忽略。 */
internal fun SourcePlayerLyricsController.sdkMotion(check: (Boolean) -> Boolean, blocked: () -> Boolean,
    density: () -> Float, panel: @Composable (SourcePlayerLyricsStyle, Modifier) -> Unit): SourcePlayerLyricsMotion {
    val controller = this
    return object : SourcePlayerLyricsMotion {
        override val opened get() = controller.requested
        override val lyricsProgress get() = motion.lyricsProgress
        override val headerProgress get() = motion.headerProgress
        override val dragging get() = motion.dragging
        override fun setOpened(opened: Boolean) { if (check(true)) request(opened) }
        override fun dragStart(): Boolean = check(true) && !blocked() && controller.dragStart()
        override fun drag(deltaPx: Float) { if (check(false)) controller.drag(deltaPx) }
        override fun dragEnd(velocityPxPerSecond: Float) {
            if (check(false)) controller.dragEnd(velocityPxPerSecond, density()) else controller.dragCancel()
        }
        override fun dragCancel() { check(false); controller.dragCancel() }
        @Composable override fun LyricsPanel(style: SourcePlayerLyricsStyle, modifier: Modifier) {
            check(false)
            panel(style, modifier)
        }
    }
}
