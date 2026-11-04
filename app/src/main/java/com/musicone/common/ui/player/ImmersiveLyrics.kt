package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlin.math.abs

internal fun activeLyric(lines: List<TimedLyric>, progressMs: Long): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= progressMs) low = middle + 1 else high = middle
    }
    return (low - 1).coerceAtLeast(0)
}

internal fun lyricBlurDp(index: Int, current: Int): Float = if (index > current) 2f else 0f

internal fun mergeLyricTranslation(
    original: List<TimedLyric>,
    translated: List<TimedLyric>,
    toleranceMs: Long = 600L,
): List<TimedLyric> {
    if (original.isEmpty() || translated.isEmpty()) return original
    return original.map { line ->
        val translation = translated.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
            ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= toleranceMs }
            ?.text
            ?.takeIf {
                it.isNotBlank() &&
                it != line.text &&
                it.trim() != "//" &&
                !it.matches(Regex("^/+$"))
            }
        line.copy(translation = translation)
    }
}

@Composable
internal fun ImmersiveLyrics(
    track: LyricPresentation,
    progressMs: () -> Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier,
    active: Boolean = true,
    followPlayback: Boolean = active,
    verbatimProgressMs: (() -> Long)? = null,
    exitAlignment: CompletableDeferred<Unit>? = null,
    openingAlignment: CompletableDeferred<Unit>? = null,
    prepareWhileHidden: Boolean = false,
    revealTopLineBeforeEntrance: Boolean = false,
    limitTransitionHistory: Boolean = false,
    residency: LyricResidency = LyricResidency.None,
    topExtensionPx: Int = 0,
    topBufferPx: Int = 0,
    exitProgress: () -> Float = { 1f },
    entranceProgress: (() -> Float)? = null,
    heldPreparation: LyricHeldWindowPreparation? = null,
    freezeWindow: Boolean = false,
    readingBounds: LyricReadingBounds? = null,
    boundaryWindow: LyricBoundaryWindow? = null,
    typography: LyricTypography,
    seeking: () -> Boolean = { false },
    intermediateSeek: Boolean = false,
    lyricsGestureHeld: Boolean = false,
    closingLyricsDrag: Boolean = false,
    progressLyricSeek: ProgressLyricSeek? = null,
    intermediateCommit: ProgressLyricSeekCommit? = null,
    intermediateCommitPending: Boolean = false,
    onIntermediateSeekOwnership: (Boolean) -> Unit = {},
    onReady: (() -> Unit)? = null,
    onWindowLayout: ((androidx.compose.foundation.lazy.LazyListLayoutInfo, LyricBlurProtection) -> Unit)? = null,
    tapFocusProgressMs: () -> Long = progressMs,
    onRetryLyrics: () -> Unit = {},
    registerTrackFreeze: ((() -> Unit) -> Unit)? = null,
    layoutReadiness: LyricLayoutReadiness? = null,
    readingAnchor: PhoneLyricsReadingAnchor? = null,
    displayRequest: Any? = null,
    displayFallback: Boolean = false,
    residentStandby: LyricResidentStandby? = null,
) {
    val onlineLines = rememberOnlineLyricLines(track)
    val wordProgress = remember(verbatimProgressMs, track.timing) {
        verbatimProgressMs?.let { clock -> { lyricWordDisplayPosition(clock(), track.timing) } }
    }
    val lines = remember(track.id, onlineLines, track.retryable) {
        onlineLines.ifEmpty { listOf(TimedLyric(0L, if (track.retryable) "歌词加载失败，点击重试" else "暂无歌词")) }
    }
    val tapFocus = remember(track.id, lines) { LyricTapFocus() }
    val current by remember(lines, progressMs, tapFocus, track.timing) {
        derivedStateOf { tapFocus.line ?: activeLyric(lines, lyricDisplayPosition(progressMs(), track.timing)) }
    }
    val isSeeking by remember(seeking) { derivedStateOf { seeking() } }
    val lyricClock = LocalLyricAnimationClock.current
    val scope = rememberCoroutineScope { lyricClock ?: kotlin.coroutines.EmptyCoroutineContext }
    val lyricSettle = remember { Animatable(1f) }

    val controller = remember(track.id) {
        LyricLayerTransitionController(track.id, current)
    }
    val middleSeek = remember(controller) {
        LyricIntermediateSeek(progressLyricSeek?.revision ?: 0L, intermediateCommit?.revision ?: 0L)
    }
    val middleSeekEnabled = active && !freezeWindow && heldPreparation == null
    val middleOwnsPosition = middleSeekEnabled && middleSeek.willOwn(isSeeking, intermediateSeek, progressLyricSeek, intermediateCommit)
    LaunchedEffect(current, isSeeking, middleOwnsPosition, middleSeek.revision, freezeWindow) {
        // 首个预览目标也由定位事务发布，不能先把旧列表的高亮改成目标句。
        if (!middleOwnsPosition && !freezeWindow) controller.updatePlaybackLine(current, present = !isSeeking)
    }

    val activePane = controller.currentPane
    val drawEpoch = LocalPlayerLyricsContent.current?.drawEpoch
    val openingMotion = LocalRetainedPhoneLyricsMotion.current
    val preparationTurn = LocalPhonePlayerTurn.current
    val preparationRotation = LocalPhonePlayerRotation.current
    val drawingWindow = LocalPhoneLyricsDrawingWindow.current
    val turnFocus = LocalPhoneLyricsTurnFocus.current
    BindLyricPreparation(controller, drawEpoch, LocalPhonePlayerPresentation.current?.preparation, openingMotion)
    val shortSeek = remember(activePane) { LyricShortSeek() }
    val autoRefocus = remember(activePane) { LyricAutoRefocus() }
    val gestureRefocus = remember(controller) { LyricGestureRefocus() }
    SideEffect {
        registerTrackFreeze?.invoke {
            controller.freezeForTrackFade()
            shortSeek.cancel()
            autoRefocus.cancel()
        }
    }
    DisposableEffect(autoRefocus) { onDispose { autoRefocus.cancel() } }
    DisposableEffect(shortSeek) { onDispose { shortSeek.cancel() } }
    val transitionHistory = activePane.transitionHistory
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lyricDensity = remember(density.density) { lyricDisplayDensity(density) }
    val latestMiddleProgress by rememberUpdatedState(entranceProgress ?: { 1f - exitProgress() })
    LyricSeekPreviewEffect(controller, current, isSeeking, active && followPlayback && !middleOwnsPosition && !freezeWindow)
    LyricAnimationEffect(activePane.playbackStep, freezeWindow, residency.pauseWords) {
        if (!freezeWindow && !residency.pauseWords) activePane.playbackStep.run()
    }
    LyricAnimationEffect(freezeWindow, isSeeking) {
        if (freezeWindow || isSeeking) {
            shortSeek.cancel()
            autoRefocus.cancel()
            if (isSeeking) gestureRefocus.cancel()
            if (freezeWindow) activePane.listState.scroll(MutatePriority.PreventUserInput) { }
        }
    }

    var handledProgressLyricSeek by remember(track.id) {
        mutableLongStateOf(progressLyricSeek?.revision ?: 0L)
    }
    val dragging by activePane.listState.interactionSource.collectIsDraggedAsState()
    LyricTapFocusEffect(tapFocus, progressMs, tapFocusProgressMs,
        isSeeking || intermediateSeek || middleOwnsPosition || dragging,
        progressLyricSeek?.revision, paused = freezeWindow)
    var follow by remember(track.id) { mutableStateOf(true) }
    SideEffect {
        val displayed = if (controller.state == LyricLayerTransitionState.PREPARING)
            controller.outgoingPane ?: activePane else activePane
        readingAnchor?.bind(track.id, displayed, if (displayed === activePane) current else displayed.currentLine, follow,
            drawingWindow)
    }
    LyricAnimationEffect(middleOwnsPosition) {
        if (middleOwnsPosition) {
            shortSeek.cancel()
            autoRefocus.cancel()
            follow = true
        }
    }

    val latestExitProgress by rememberUpdatedState(exitProgress)
    val latestEntranceProgress by rememberUpdatedState(entranceProgress)
    LyricAnimationEffect(heldPreparation) {
        val request = heldPreparation ?: return@LyricAnimationEffect
        controller.cancel()
        lyricSettle.snapTo(1f)
        activePane.playbackStep.reset(current)
        follow = true
        topLinePreparationLoop(request, activePane.listState, transitionHistory, lyricDensity, typography,
            { activeLyric(lines, lyricDisplayPosition(progressMs(), track.timing)) },
            { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) }, onLine = { line ->
            // 中间态跨句也同步隐藏目标的焦点，不能永远等待旧句的绘制确认。
            controller.updatePlaybackLine(line, presentDuringTransition = true)
            activePane.playbackStep.reset(line)
        }, stamp = { lyricDisplayStamp(activePane, track.id, preparationRotation) })
    }
    // 收起时保留历史准备几何；反向期间由原对齐动画继续接管位置。
    val closingWindow = exitAlignment != null || closingLyricsDrag
    val historyPhase by remember {
        derivedStateOf {
            val progress = 1f - latestExitProgress()
            when { progress <= 0f -> 0f; progress >= 1f -> 1f; else -> .5f }
        }
    }
    // 准备快照只影响行程测量，不再改变历史行的显示资格。
    if (!freezeWindow && !middleOwnsPosition) transitionHistory.update(historyPhase,
        limitTransitionHistory && follow && !isSeeking && !shortSeek.active &&
            !controller.previewActive && controller.state == LyricLayerTransitionState.NORMAL,
        closingWindow = closingWindow)
    val alignDuringEntrance = revealTopLineBeforeEntrance || entranceProgress != null
    val entrancePositionOwned by remember(active, entranceProgress, closingLyricsDrag) { derivedStateOf {
        active && !closingLyricsDrag && entranceProgress?.invoke()?.let { it < 1f } == true
    } }
    val topLineAlignment = rememberLyricTopLineAlignment(
        activePane, alignDuringEntrance, active, prepareWhileHidden,
        openingAlignment, exitAlignment,
        { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) },
        positionOwned = middleOwnsPosition || closingLyricsDrag || autoRefocus.active || freezeWindow,
    )
    LyricIntermediateSeekEffect(middleSeek, controller, current, isSeeking, intermediateSeek,
        lyricsGestureHeld, middleSeekEnabled, progressLyricSeek,
        progressLyricSeek?.let { activeLyric(lines, lyricDisplayPosition((track.durationMs * it.fraction).toLong(), track.timing)) },
        intermediateCommit?.takeIf { intermediateCommitPending }?.let { activeLyric(lines, lyricDisplayPosition(it.positionMs, track.timing)) },
        intermediateCommit,
        { latestMiddleProgress() },
        windowOffset = { boundaryWindow?.offset() ?: 0f },
        windowVelocity = { boundaryWindow?.velocity() ?: 0f },
        onTakeover = { topLineAlignment.acceptSeekAlignment() },
        onOwnership = onIntermediateSeekOwnership,
        onAligned = { topLineAlignment.acceptSeekAlignment() })
    val firstPlacement = rememberLyricFirstPlacement(activePane,
        enabled = !revealTopLineBeforeEntrance,
        allowed = !displayFallback && !controller.displayFallback && follow && !residency.prepareClosed && !prepareWhileHidden && openingAlignment == null &&
            heldPreparation == null && !entrancePositionOwned && !dragging && !freezeWindow && !middleOwnsPosition && !isSeeking &&
            !shortSeek.active && !autoRefocus.active && !controller.previewActive &&
            controller.state == LyricLayerTransitionState.NORMAL,
        current = current)
    val firstPlacementReady by firstPlacement.ready
    val playbackFollowing = followPlayback && firstPlacementReady &&
        (!alignDuringEntrance || topLineAlignment.playbackScroll != null ||
            !topLineAlignment.pending && !entrancePositionOwned)
    LyricResidentAlignmentEffect(residency, activePane, current,
        !freezeWindow && !middleOwnsPosition && !isSeeking && !controller.previewActive &&
            controller.state == LyricLayerTransitionState.NORMAL, typography) {
        follow = true
        topLineAlignment.acceptSeekAlignment()
    }
    LyricGestureRefocusEffect(gestureRefocus, controller, autoRefocus, topLineAlignment,
        lyricsGestureHeld, active,
        followPlayback && !freezeWindow && !middleOwnsPosition && !isSeeking && !shortSeek.active && !controller.previewActive,
        current, { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) }, { follow = true })

    LyricAnimationEffect(activePane, dragging, isSeeking, freezeWindow, active, playbackFollowing,
        controller.state, controller.previewActive, shortSeek.active, middleOwnsPosition, gestureRefocus.pending) {
        if (freezeWindow || middleOwnsPosition || gestureRefocus.pending) return@LyricAnimationEffect
        if (isSeeking) {
            follow = true
        } else if (dragging) {
            follow = false
        } else if (!follow) {
            if (!active || !playbackFollowing || controller.previewActive ||
                controller.state != LyricLayerTransitionState.NORMAL || shortSeek.active) return@LyricAnimationEffect
            snapshotFlow { activePane.listState.isScrollInProgress }.first { !it }
            delay(3500)
            refocusLyricPlayback(controller, activePane, autoRefocus, scope,
                current = { activeLyric(lines, lyricDisplayPosition(progressMs(), track.timing)) },
                allowed = { !dragging && !isSeeking && !freezeWindow && active && playbackFollowing &&
                    !shortSeek.active && !controller.previewActive && !middleOwnsPosition &&
                    controller.currentPane === activePane && controller.state == LyricLayerTransitionState.NORMAL },
                onAligned = { follow = true })
        }
    }

    LyricAnimationEffect(exitAlignment, middleOwnsPosition, freezeWindow) {
        if (freezeWindow) return@LyricAnimationEffect
        exitAlignment?.let { request ->
            if (middleOwnsPosition) { request.complete(Unit); return@LyricAnimationEffect }
            activePane.listState.alignForLyricsExit(request, { latestExitProgress() }) { _ -> }
        }
    }

    val openingViewport by remember(activePane) {
        derivedStateOf { activePane.listState.layoutInfo.viewportSize }
    }
    val openingStandby = remember(activePane, lines, typography, lyricDensity, topBufferPx,
        revealTopLineBeforeEntrance, limitTransitionHistory) { LyricOpeningStandby() }
    val openingDrawProof = remember(activePane) { LyricOpeningDrawProof() }
    val standbyOwner = remember { Any() }
    SideEffect {
        residentStandby?.bind(standbyOwner) {
            if (prepareWhileHidden || heldPreparation != null || displayFallback || controller.displayFallback ||
                freezeWindow || middleOwnsPosition || isSeeking || controller.previewActive ||
                shortSeek.active || autoRefocus.active || activePane.listState.isScrollInProgress ||
                controller.state != LyricLayerTransitionState.NORMAL) null
            else lyricDisplayStamp(activePane, track.id, preparationRotation)?.let { LyricStandbyFrame(it, current) }
        }
    }
    DisposableEffect(residentStandby, standbyOwner) {
        onDispose { residentStandby?.release(standbyOwner) }
    }
    ObserveLyricDisplay(drawEpoch, controller, listOf(activePane, controller.outgoingPane, controller.previewPane),
        listOf(openingAlignment, openingDrawProof.revision, heldPreparation, displayRequest, displayFallback,
            controller.displayFallback, controller.displayRevision,
            LocalPhoneLyricsMountRevision.current, preparationTurn?.generation, preparationRotation,
            prepareWhileHidden, residency.prepareClosed))
    LyricAnimationEffect(openingAlignment, prepareWhileHidden, if (prepareWhileHidden || openingAlignment != null) current else null,
        openingStandby,
        if (prepareWhileHidden && revealTopLineBeforeEntrance) openingViewport else null,
        if (openingAlignment != null) listOf(activePane.listHost.revision, openingViewport, preparationRotation) else null) {
        if (!middleOwnsPosition && (openingAlignment != null || prepareWhileHidden)) {
            suspend fun alignOpening() {
                val reuse = !activePane.listState.isScrollInProgress &&
                    openingStandby.matches(current, activePane.listState.layoutInfo, transitionHistory.openingContext)
                if (!reuse) {
                    openingStandby.invalidate()
                    controller.cancel()
                    controller.updatePlaybackLine(current)
                    progressLyricSeek?.sourceIsolated?.complete(Unit)
                    progressLyricSeek?.targetReady?.complete(Unit)
                    lyricSettle.snapTo(1f)
                    activePane.playbackStep.reset(current)
                    follow = true
                    activePane.listState.scrollToItem(current)
                    if (revealTopLineBeforeEntrance) {
                        topLineAlignment.prepare(current)
                        if (limitTransitionHistory) {
                            activePane.listState.prepareOpeningContext(transitionHistory, current, lyricDensity, topBufferPx, typography)
                        } else activePane.listState.revealTopLineForEntrance()
                    }
                    openingStandby.record(current, activePane.listState.layoutInfo, transitionHistory.openingContext)
                }
                // 首开采用屏外准备后的实测行块，不能沿用上一帧绘制记录。
                onWindowLayout?.invoke(transitionHistory.openingContext?.compactLayout ?: activePane.listState.layoutInfo,
                    lyricBlurProtection(activePane.listState.layoutInfo, current,
                        activePane.playbackStep.offsetPx(current), true))
                if (openingAlignment != null) {
                    // 常驻命中也重新交接本轮焦点，不能沿用上次已经完成的展开资格。
                    topLineAlignment.prepare(current)
                    snapshotFlow { lyricDisplayStamp(activePane, track.id, preparationRotation) }.first { it != null }
                    openingDrawProof.awaitDraw(current, lyricDisplayStamp(activePane, track.id, preparationRotation))
                }
            }
            val request = openingAlignment
            if (request == null || layoutReadiness == null || preparationTurn == null) {
                alignOpening()
            } else {
                val remaining = ((openingMotion?.openingDeadlineNanos ?: Long.MAX_VALUE) - System.nanoTime())
                    .coerceAtLeast(0L).div(1_000_000L).coerceAtMost(PHONE_PLAYER_TURN_PREPARATION_MILLIS)
                val prepared = withTimeoutOrNull(remaining) {
                    alignOpening()
                    val stable = LyricStableFrames()
                    while (!stable.observe(layoutReadiness.geometry(preparationTurn.generation, preparationRotation)))
                        withFrameNanos { }
                    true
                } == true
                if (prepared) residentStandby?.confirmOpening(preparationTurn.generation)
                else openingMotion?.openingFailed(request)
            }
            request?.complete(Unit)
        }
    }

    val pendingProgressSeek = progressLyricSeek?.takeIf {
        it.trackId == track.id && it.revision != handledProgressLyricSeek && it.revision != middleSeek.handledTap
    }

    LyricAnimationEffect(track.id, pendingProgressSeek?.revision, active, playbackFollowing, isSeeking, controller.previewActive, middleOwnsPosition, freezeWindow) {
        pendingProgressSeek?.let { request ->
            // 转屏期间保留请求，释放阅读位置后处理最新目标，不能确认后丢弃。
            if (freezeWindow) return@LyricAnimationEffect
            if (middleOwnsPosition) return@LyricAnimationEffect
            autoRefocus.cancel()
            // 松手交接尚未结束的新单击稍后再处理，不能把它误当作拖动取消掉。
            if (!isSeeking && controller.previewActive) return@LyricAnimationEffect
            handledProgressLyricSeek = request.revision
            if (!active || !playbackFollowing || isSeeking) {
                request.sourceIsolated.complete(Unit)
                request.targetReady.complete(Unit)
                return@LyricAnimationEffect
            }
            val targetIndex = activeLyric(lines, lyricDisplayPosition((track.durationMs * request.fraction).toLong(), track.timing))
            val listState = activePane.listState
            val trulyVisible = isLyricTargetTrulyVisible(listState, targetIndex)
            val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, targetIndex)
            follow = true
            if (trulyVisible || closeDistance) {
                // 先占有滚动权，再发布音频 seek，避免正常跟随抢占同一次点击。
                shortSeek.start(scope, activePane, targetIndex) { request.targetReady.complete(Unit) }
                request.sourceIsolated.complete(Unit)
            } else {
                val direction = if (targetIndex > listState.firstVisibleItemIndex) 1f else -1f
                val height = listState.layoutInfo.viewportSize.height.toFloat()
                controller.beginTransition(
                    targetLine = targetIndex,
                    travelDirection = direction,
                    viewportHeight = height,
                    scope = scope,
                    onSourceIsolated = { request.sourceIsolated.complete(Unit) },
                    onTargetReady = { request.targetReady.complete(Unit) },
                )
            }
        }
    }

    // 正常播放期间的单句步进与跨多句跟随
    val naturalStepFollowing by rememberUpdatedState(pendingProgressSeek == null && active && playbackFollowing && follow && !dragging && !gestureRefocus.pending &&
        !freezeWindow && !middleOwnsPosition && !isSeeking && !shortSeek.active && !autoRefocus.active && !controller.previewActive &&
        controller.state == LyricLayerTransitionState.NORMAL)
    LyricAnimationEffect(activePane, activePane.listHost.revision, current, follow, playbackFollowing, active, isSeeking, controller.state, controller.previewActive, shortSeek.active, autoRefocus.active, freezeWindow, middleOwnsPosition, gestureRefocus.pending, pendingProgressSeek?.revision) {
        if (pendingProgressSeek == null && activePane.listHost.ready && active && playbackFollowing && follow && !gestureRefocus.pending && !freezeWindow && !middleOwnsPosition && !isSeeking && !shortSeek.active && !autoRefocus.active && !controller.previewActive && controller.state == LyricLayerTransitionState.NORMAL) {
            val listState = activePane.listState
            val stepChange = activePane.playbackStep.consume(
                current,
                enabled = !dragging,
                resumeInterrupted = true,
            )
            when (stepChange) {
                LyricPlaybackStepChange.NONE -> {
                    // 若交接结束后存在未同步的最新句，主动补位定位，避免遗漏
                    val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                    if (topLineAlignment.playbackScroll == null &&
                        (targetItem == null || !isLyricTargetTrulyVisible(listState, current))) {
                        val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, current)
                        if (closeDistance) {
                            listState.animateScrollToItem(current)
                        } else {
                            val direction = if (current > listState.firstVisibleItemIndex) 1f else -1f
                            val height = listState.layoutInfo.viewportSize.height.toFloat()
                            controller.beginTransition(
                                targetLine = current,
                                travelDirection = direction,
                                viewportHeight = height,
                                scope = scope,
                            )
                        }
                    }
                }
                LyricPlaybackStepChange.ANIMATE -> {
                    runLyricNaturalListStep(activePane, current, topLineAlignment.playbackScroll) {
                        naturalStepFollowing && controller.currentPane === activePane
                    }
                }
                LyricPlaybackStepChange.MULTI_STEP -> {
                    // 已测量的跨句沿用自然逐行位移；未测量目标复用屏外窗口准备。
                    val entrance = topLineAlignment.playbackScroll
                    val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                    if (entrance != null) {
                        runLyricNaturalListStep(activePane, current, entrance) {
                            naturalStepFollowing && controller.currentPane === activePane
                        }
                    } else if (targetItem != null) {
                        runLyricNaturalPlaybackStep(activePane.playbackStep, current,
                            listState.layoutInfo.visibleItemsInfo.map { it.index }, lyricVisibleSeekTravel(targetItem.offset),
                            { naturalStepFollowing && controller.currentPane === activePane },
                            canScroll = activePane.listHost.scrollLease()) { move ->
                            listState.scroll { move { scrollBy(it) } }
                        }
                    } else {
                        val direction = if (current > listState.firstVisibleItemIndex) 1f else -1f
                        val height = listState.layoutInfo.viewportSize.height.toFloat()
                        controller.beginTransition(
                            targetLine = current,
                            travelDirection = direction,
                            viewportHeight = height,
                            scope = scope,
                            purpose = LyricPlaybackMotionPurpose.PLAYBACK,
                        )
                    }
                }
                LyricPlaybackStepChange.RESET -> {
                    activePane.playbackStep.reset(current)
                }
            }
        }
    }

    val seekReadiness = remember(controller) { LyricLayoutReadiness() }
    val displayReadiness = layoutReadiness ?: seekReadiness.takeIf {
        controller.state == LyricLayerTransitionState.PREPARING
    }
    BoxWithConstraints(modifier.widthIn(max = 760.dp)) {
        val viewportGeometry = if (LocalPhonePlayerTurn.current != null) LyricViewportGeometry(
            LocalPhonePlayerRotation.current, with(density) { maxWidth.roundToPx() },
            with(density) { maxHeight.roundToPx() }) else null
        val layoutOwner = remember(controller) { Any() }
        val layoutStamp = displayReadiness?.let {
            val preparedLine by remember(activePane) { derivedStateOf {
                readingAnchor?.position?.line ?: activePane.listState.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) }?.index
                    ?: activePane.currentLine
            } }
            LyricLayoutStamp(layoutOwner, LocalPhonePlayerTurn.current?.generation ?: 0, LocalPhonePlayerRotation.current,
                with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() },
                preparedLine, lines.getOrNull(preparedLine)?.translation != null,
                with(density) { LocalLandscapeLyricsTouchPadding.current.roundToPx() },
                pane = activePane, mount = activePane.listHost.revision,
                preparation = listOf(openingAlignment, openingDrawProof.revision,
                    heldPreparation, displayRequest, controller.displayRevision))
        }
        SideEffect { if (layoutStamp != null) displayReadiness?.expect(layoutStamp) }
        DisposableEffect(displayReadiness, layoutOwner) { onDispose { displayReadiness?.clear(layoutOwner) } }
        val anchor = lyricReadingAnchorDp(maxHeight.value, readingBounds, density,
            typography.estimatedRowHeightDp).dp
        LyricTurnReadingPreparation(controller, readingAnchor, preparationTurn, preparationRotation,
            LyricPlacementGeometry(with(density) { maxWidth.roundToPx() },
                with(density) { maxHeight.roundToPx() }, with(density) { anchor.roundToPx() }),
            topLineAlignment, gestureRefocus) { follow = it }
        val preparingHistory by remember(limitTransitionHistory, heldPreparation, transitionHistory) {
            derivedStateOf { limitTransitionHistory && !transitionHistory.preservingCloseWindow &&
                (heldPreparation != null || latestExitProgress() > 0f) }
        }
        val extraPaddingFraction = rememberLyricIntermediateSeekPaddingFraction(
            preparingHistory, middleOwnsPosition)
        // 寻焦落位前保留末尾行程，不能在松手收尾时同步压缩列表边界。
        val bottomPadding = (maxHeight - anchor).coerceAtLeast(72.dp) +
            anchor * extraPaddingFraction

        val notifyPaneDrawn: (LyricWindowPane) -> Unit = notify@{ currentDisplayPane ->
            val info = currentDisplayPane.listState.layoutInfo
            val stamp = lyricDisplayStamp(currentDisplayPane, track.id, preparationRotation)
            val glyphReady = layoutStamp == null || (layoutStamp.pane === currentDisplayPane &&
                displayReadiness?.geometry(layoutStamp.generation, layoutStamp.rotation) != null)
            val currentItem = info.visibleItemsInfo.firstOrNull { it.index == current }
            if (currentDisplayPane === activePane) turnFocus?.record(preparationTurn?.generation ?: 0,
                info.viewportSize.height, info.viewportStartOffset,
                if (currentDisplayPane.currentLine == current) currentItem else
                    info.visibleItemsInfo.firstOrNull { it.index == currentDisplayPane.currentLine },
                currentDisplayPane.playbackStep.offsetPx(currentDisplayPane.currentLine),
                controller.state == LyricLayerTransitionState.NORMAL && !controller.previewActive)
            if (currentDisplayPane === activePane) {
                openingDrawProof.drawn(current, info.viewportSize.width > 0 && info.viewportSize.height > 0,
                    currentItem != null && glyphReady, currentDisplayPane.listState.isScrollInProgress, stamp)
            }
            // 只有关闭态已绘制到阅读锚点才算可直开，读取结果不增加额外绘制订阅。
            if (currentDisplayPane === activePane && residency.prepareClosed) residentStandby?.drawn(standbyOwner,
                stamp?.takeIf { lyricWindowAligned(currentItem?.offset, currentDisplayPane.listState.isScrollInProgress,
                    currentDisplayPane.playbackStep.offsetPx(current)) }?.let { LyricStandbyFrame(it, current) })
            if (currentDisplayPane === activePane) firstPlacement.onDrawn(active &&
                !prepareWhileHidden && !residency.prepareClosed &&
                controller.state != LyricLayerTransitionState.PREPARING)
            if (!firstPlacementReady) return@notify
            onWindowLayout?.let { record ->
                record(transitionHistory.openingContext?.compactLayout ?: info,
                    lyricBlurProtection(info.viewportStartOffset,
                    if (currentDisplayPane.currentLine == current) currentItem else
                        info.visibleItemsInfo.firstOrNull { it.index == currentDisplayPane.currentLine },
                    currentDisplayPane.playbackStep.offsetPx(currentDisplayPane.currentLine),
                    controller.state == LyricLayerTransitionState.NORMAL && !controller.previewActive))
            }
            if (!glyphReady) return@notify
            if (currentDisplayPane.currentLine == current) heldPreparation?.drawn(current,
                currentItem?.offset,
                latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()),
                controller.drawingOffset, currentDisplayPane.listState.isScrollInProgress, stamp)
            if (!currentDisplayPane.drawnFirstFrame &&
                currentDisplayPane.id == controller.currentPane.id &&
                (if (currentDisplayPane.initialLine == current) currentItem != null else
                    info.visibleItemsInfo.any { it.index == currentDisplayPane.initialLine })
            ) {
                currentDisplayPane.drawnFirstFrame = true
                currentDisplayPane.ready.complete(Unit)
            }
            if (onReady != null && lyricWindowAligned(
                    currentItem?.offset,
                    currentDisplayPane.listState.isScrollInProgress,
                    controller.drawingOffset,
                )
            ) onReady()

        }

        val paneDrawObserver = rememberUpdatedState(notifyPaneDrawn)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LyricWindowStages(controller, onDrawn = notifyPaneDrawn) { currentDisplayPane, outgoing ->
                    val entering = !outgoing && controller.outgoingPane != null
                    SideEffect { currentDisplayPane.playbackStep.enterWords(entering) }
                    val layoutProof = remember(displayReadiness, layoutStamp, currentDisplayPane, outgoing) {
                        if (!outgoing && displayReadiness != null && layoutStamp != null)
                            LyricLayoutProof(displayReadiness, layoutStamp, currentDisplayPane,
                                drawingRange = if (drawingWindow.extended)
                                    LyricDrawingRangeProof(currentDisplayPane, lines.map { it.translation != null }) else null) {
                                paneDrawObserver.value(currentDisplayPane)
                            } else null
                    }
                    LyricListView(
                        pane = currentDisplayPane,
                        lines = lines,
                        anchor = anchor,
                        bottomPadding = bottomPadding,
                        typography = typography,
                        active = !outgoing && active && playbackFollowing,
                        isSeeking = !outgoing && isSeeking,
                        shortSeeking = !outgoing && shortSeek.active,
                        animateEmphasis = !residency.pauseWords,
                        freezeVisual = freezeWindow,
                        holdWords = outgoing,
                        boundaryWindow = boundaryWindow,
                        paneOffset = { controller.paneTranslation(currentDisplayPane) },
                        lyricSettleProgress = { if (outgoing) 1f else lyricSettle.value },
                        smoothProgressMs = if (outgoing) null else wordProgress,
                        layoutProof = layoutProof,
                        readingAnchor = if (outgoing) null else readingAnchor,
                        viewportGeometry = viewportGeometry,
                        modifier = Modifier.graphicsLayer {
                            alpha = if (outgoing || firstPlacementReady) 1f else 0f
                            PlayerMotionDiagnostics.sample("lyric-list-gate/${currentDisplayPane.id}") {
                                "alpha=$alpha ready=$firstPlacementReady outgoing=$outgoing current=$current " +
                                    "paneLine=${currentDisplayPane.currentLine} active=$active follow=$playbackFollowing " +
                                    "preparing=$prepareWhileHidden frozen=$freezeWindow hostReady=${currentDisplayPane.listHost.ready} " +
                                    "viewport=${currentDisplayPane.listState.layoutInfo.viewportSize} " +
                                    "rows=${currentDisplayPane.listState.layoutInfo.visibleItemsInfo.map { it.index to it.offset }}"
                            }
                        },
                        onLineClick = lineClick@{ index, line ->
                            if (track.retryable) {
                                onRetryLyrics()
                                return@lineClick
                            }
                            middleSeek.cancel()
                            autoRefocus.cancel()
                            val fraction = lyricSeekFraction(line.timeMs, track.durationMs)
                            tapFocus.request(index, line.timeMs, (track.durationMs * fraction).toLong(),
                                tapFocusProgressMs())
                            val listState = currentDisplayPane.listState
                            val trulyVisible = isLyricTargetTrulyVisible(listState, index)
                            val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, index)
                            follow = true
                            if (trulyVisible || closeDistance) {
                                controller.updatePlaybackLine(index)
                                shortSeek.start(scope, currentDisplayPane, index)
                                onSeek(fraction)
                            } else {
                                val direction = if (index > listState.firstVisibleItemIndex) 1f else -1f
                                val height = listState.layoutInfo.viewportSize.height.toFloat()
                                controller.beginTransition(
                                    targetLine = index,
                                    travelDirection = direction,
                                    viewportHeight = height,
                                    scope = scope,
                                )
                                onSeek(fraction)
                            }
                        },
                    )
                }
                LyricSeekPreviewTarget(controller, lines, anchor, bottomPadding, typography)
            }
        }
    }
}


@Composable
internal fun LyricListView(
    pane: LyricWindowPane,
    lines: List<TimedLyric>,
    anchor: Dp,
    bottomPadding: Dp,
    typography: LyricTypography,
    active: Boolean,
    isSeeking: Boolean,
    lyricSettleProgress: () -> Float,
    onLineClick: (Int, TimedLyric) -> Unit,
    modifier: Modifier = Modifier,
    shortSeeking: Boolean = false,
    animateEmphasis: Boolean = true,
    smoothProgressMs: (() -> Long)? = null,
    boundaryWindow: LyricBoundaryWindow? = null,
    paneOffset: () -> Float = { 0f },
    freezeVisual: Boolean = false,
    holdWords: Boolean = false,
    layoutProof: LyricLayoutProof? = null,
    readingAnchor: PhoneLyricsReadingAnchor? = null,
    viewportGeometry: LyricViewportGeometry? = null,
) {
    val latestPaneOffset by rememberUpdatedState(paneOffset)
    val boundaryLayout = remember(pane) { LyricBoundaryLayout(pane.listState) }
    val lineWindow = remember(boundaryWindow) { boundaryWindow?.let { bounds ->
        LyricBoundaryWindow(bounds.topBuffer,
            offset = { bounds.offset() + latestPaneOffset() }, velocity = bounds.velocity,
            edges = bounds.edges, partialTop = bounds.partialTop)
    } }
    val boundaryViewport = remember(lineWindow, boundaryLayout) {
        lineWindow?.let { LyricBoundaryViewport(it, boundaryLayout::height) }
    }
    val fling = rememberLyricFlingBinding()
    LazyColumn(
        state = pane.listState,
        modifier = modifier.fillMaxSize().lyricListHost(pane, LocalPhonePlayerTurn.current).then(fling.modifier),
        flingBehavior = fling.behavior,
        userScrollEnabled = active && !pane.isFrozen,
        contentPadding = PaddingValues(start = LocalLandscapeLyricsTouchPadding.current,
            top = anchor, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(
            lines,
            key = { index, line -> "$index-${line.timeMs}" },
            contentType = { _, line -> line.translation != null },
        ) { index, line ->
            ImmersiveLyricLine(
                line = line,
                index = index,
                current = pane.currentLine,
                typography = typography,
                layoutProof = layoutProof,
                readingAnchor = readingAnchor,
                readingPane = pane,
                active = active && !pane.isFrozen,
                animateEmphasis = !pane.isFrozen && animateEmphasis,
                freezeVisual = freezeVisual,
                holdWords = holdWords || pane.isFrozen,
                settleProgress = lyricSettleProgress,
                playbackMotion = pane.playbackStep,
                playbackStepActive = active && !pane.isFrozen && !isSeeking && !shortSeeking,
                smoothProgressMs = smoothProgressMs,
                onClick = { onLineClick(index, line) },
                boundary = remember(lineWindow, boundaryLayout, boundaryViewport, index, viewportGeometry) {
                    lineWindow?.let { bounds -> LyricLineBoundary(bounds,
                        rowTop = { boundaryLayout.top(index) }, viewportHeight = boundaryLayout::height,
                        geometry = viewportGeometry, viewport = checkNotNull(boundaryViewport)) }
                },
            )
        }
    }
}

internal fun shouldAnimateLyricScroll(visibleIndex: Int, targetIndex: Int): Boolean =
    abs(targetIndex - visibleIndex) <= 2

internal fun lyricSeekLayerAlpha(
    offset: Float,
    travel: Float,
    outgoing: Boolean,
    crossfade: Boolean,
): Float {
    if (offset == 0f) return if (outgoing) 0f else 1f
    if (!crossfade || travel == 0f) return 1f
    val remaining = (abs(offset) / abs(travel)).coerceIn(0f, 1f)
    val easedRemaining = remaining * remaining * (3f - 2f * remaining)
    return if (outgoing) easedRemaining else 1f - easedRemaining
}

internal enum class LyricScrollMode { SNAP, ANIMATE, CHANGE_WINDOW }

internal fun lyricScrollMode(
    seeking: Boolean,
    visibleIndex: Int,
    targetIndex: Int,
    targetVisible: Boolean = false,
): LyricScrollMode = when {
    seeking -> LyricScrollMode.SNAP
    targetVisible || shouldAnimateLyricScroll(visibleIndex, targetIndex) -> LyricScrollMode.ANIMATE
    else -> LyricScrollMode.CHANGE_WINDOW
}

private suspend fun LazyListState.alignForLyricsExit(
    ready: CompletableDeferred<Unit>,
    progress: () -> Float,
    onProgress: suspend (Float) -> Unit,
) {
    scroll(MutatePriority.PreventUserInput) {
        val layout = layoutInfo
        val delta = layout.visibleItemsInfo.firstNotNullOfOrNull {
            lyricExitScrollDelta(it.offset, it.size, layout.viewportStartOffset)
        } ?: 0f
        val start = progress()
        var applied = 0f
        ready.complete(Unit)
        if (start >= 1f) return@scroll
        snapshotFlow { lyricExitRevealFraction(progress(), start) }.takeWhile { fraction ->
            val target = delta * fraction
            applied += scrollBy(target - applied)
            onProgress(fraction)
            fraction < 1f
        }.collect()
    }
}

internal fun lyricExitRevealFraction(progress: Float, start: Float): Float {
    val fraction = ((progress - start) / ((1f - start) * .35f).coerceAtLeast(.0005f)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

internal fun lyricExitScrollDelta(offset: Int, size: Int, viewportStartOffset: Int): Float? =
    if (offset < viewportStartOffset && offset + size > viewportStartOffset) {
        (offset - viewportStartOffset).toFloat()
    } else {
        null
    }
