package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
) {
    val onlineLines = rememberOnlineLyricLines(track)
    val wordProgress = remember(verbatimProgressMs, track.source) {
        verbatimProgressMs?.let { clock -> { lyricWordDisplayPosition(clock(), track.source) } }
    }
    val lines = remember(track.id, onlineLines, track.retryable) {
        onlineLines.ifEmpty { listOf(TimedLyric(0L, if (track.retryable) "歌词加载失败，点击重试" else "暂无歌词")) }
    }
    val tapFocus = remember(track.id, lines) { LyricTapFocus() }
    val current by remember(lines, progressMs, tapFocus, track.source) {
        derivedStateOf { tapFocus.line ?: activeLyric(lines, lyricDisplayPosition(progressMs(), track.source)) }
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
    LaunchedEffect(current, isSeeking, middleOwnsPosition, middleSeek.revision) {
        // 首个预览目标也由定位事务发布，不能先把旧列表的高亮改成目标句。
        if (!middleOwnsPosition) controller.updatePlaybackLine(current, present = !isSeeking)
    }

    val activePane = controller.currentPane
    val shortSeek = remember(activePane) { LyricShortSeek() }
    val autoRefocus = remember(activePane) { LyricAutoRefocus() }
    val gestureRefocus = remember(controller) { LyricGestureRefocus() }
    DisposableEffect(autoRefocus) { onDispose { autoRefocus.cancel() } }
    DisposableEffect(shortSeek) { onDispose { shortSeek.cancel() } }
    val transitionHistory = activePane.transitionHistory
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lyricDensity = remember(density.density) { lyricDisplayDensity(density) }
    val latestMiddleProgress by rememberUpdatedState(entranceProgress ?: { 1f - exitProgress() })
    LyricSeekPreviewEffect(controller, current, isSeeking, active && followPlayback && !middleOwnsPosition)
    LyricAnimationEffect(activePane.playbackStep, freezeWindow, residency.pauseWords) {
        if (!freezeWindow && !residency.pauseWords) activePane.playbackStep.run()
    }
    LyricAnimationEffect(freezeWindow) {
        if (freezeWindow) {
            shortSeek.cancel()
            activePane.listState.scroll(MutatePriority.PreventUserInput) { }
        }
    }

    var handledProgressLyricSeek by remember(track.id) {
        mutableLongStateOf(progressLyricSeek?.revision ?: 0L)
    }
    val dragging by activePane.listState.interactionSource.collectIsDraggedAsState()
    LyricTapFocusEffect(tapFocus, progressMs, tapFocusProgressMs,
        isSeeking || intermediateSeek || middleOwnsPosition || dragging || freezeWindow,
        progressLyricSeek?.revision)
    var follow by remember(track.id) { mutableStateOf(true) }
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
            { activeLyric(lines, lyricDisplayPosition(progressMs(), track.source)) },
            { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) })
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
    val topLineAlignment = rememberLyricTopLineAlignment(
        activePane.listState, revealTopLineBeforeEntrance, active, prepareWhileHidden,
        openingAlignment, exitAlignment, current,
        { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) },
        positionOwned = middleOwnsPosition || closingLyricsDrag || autoRefocus.active,
    )
    LyricIntermediateSeekEffect(middleSeek, controller, current, isSeeking, intermediateSeek,
        lyricsGestureHeld, middleSeekEnabled, progressLyricSeek,
        progressLyricSeek?.let { activeLyric(lines, lyricDisplayPosition((track.durationMs * it.fraction).toLong(), track.source)) },
        intermediateCommit?.takeIf { intermediateCommitPending }?.let { activeLyric(lines, lyricDisplayPosition(it.positionMs, track.source)) },
        intermediateCommit,
        { latestMiddleProgress() },
        windowOffset = { boundaryWindow?.offset() ?: 0f },
        windowVelocity = { boundaryWindow?.velocity() ?: 0f },
        onTakeover = { topLineAlignment.acceptSeekAlignment() },
        onOwnership = onIntermediateSeekOwnership,
        onAligned = { topLineAlignment.acceptSeekAlignment() })
    val playbackFollowing = followPlayback && (!revealTopLineBeforeEntrance || !topLineAlignment.pending)
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
            val targetIndex = activeLyric(lines, lyricDisplayPosition(progressMs(), track.source))
            val listState = activePane.listState
            val trulyVisible = isLyricTargetTrulyVisible(listState, targetIndex)
            val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, targetIndex)
            if (trulyVisible) {
                autoRefocus.align(activePane, { activeLyric(lines, lyricDisplayPosition(progressMs(), track.source)) }) {
                    !dragging && !isSeeking && !freezeWindow && active && playbackFollowing &&
                        !shortSeek.active && !controller.previewActive &&
                        controller.currentPane === activePane && controller.state == LyricLayerTransitionState.NORMAL
                }
                follow = true
            } else if (closeDistance) {
                listState.animateScrollToItem(targetIndex)
                follow = true
            } else {
                val direction = if (targetIndex > listState.firstVisibleItemIndex) 1f else -1f
                val height = listState.layoutInfo.viewportSize.height.toFloat()
                controller.beginTransition(
                    targetLine = targetIndex,
                    travelDirection = direction,
                    viewportHeight = height,
                    scope = scope,
                    onTargetReady = { follow = true },
                )
            }
        }
    }

    LyricAnimationEffect(exitAlignment, middleOwnsPosition) {
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
    LyricAnimationEffect(openingAlignment, prepareWhileHidden, if (prepareWhileHidden) current else null,
        openingStandby,
        if (prepareWhileHidden && revealTopLineBeforeEntrance) openingViewport else null) {
        if (!middleOwnsPosition && (openingAlignment != null || prepareWhileHidden)) {
            val reuse = !activePane.listState.isScrollInProgress &&
                openingStandby.matches(current, activePane.listState.layoutInfo, transitionHistory.openingContext)
            if (!reuse) {
                openingStandby.invalidate()
                controller.cancel()
                progressLyricSeek?.sourceIsolated?.complete(Unit)
                progressLyricSeek?.targetReady?.complete(Unit)
                lyricSettle.snapTo(1f)
                activePane.playbackStep.reset(current)
                follow = true
                activePane.listState.scrollToItem(current)
                if (revealTopLineBeforeEntrance) {
                    topLineAlignment.prepare(current.takeIf { limitTransitionHistory && heldPreparation == null })
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
            openingAlignment?.complete(Unit)
        }
    }

    val pendingProgressSeek = progressLyricSeek?.takeIf {
        it.trackId == track.id && it.revision != handledProgressLyricSeek && it.revision != middleSeek.handledTap
    }

    LyricAnimationEffect(track.id, pendingProgressSeek?.revision, active, playbackFollowing, isSeeking, controller.previewActive, middleOwnsPosition) {
        pendingProgressSeek?.let { request ->
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
            val targetIndex = activeLyric(lines, lyricDisplayPosition((track.durationMs * request.fraction).toLong(), track.source))
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
    val naturalStepFollowing by rememberUpdatedState(active && playbackFollowing && follow && !dragging && !gestureRefocus.pending &&
        !freezeWindow && !middleOwnsPosition && !isSeeking && !shortSeek.active && !autoRefocus.active && !controller.previewActive &&
        controller.state == LyricLayerTransitionState.NORMAL)
    LyricAnimationEffect(current, follow, playbackFollowing, active, isSeeking, controller.state, controller.previewActive, shortSeek.active, autoRefocus.active, freezeWindow, middleOwnsPosition, gestureRefocus.pending) {
        if (active && playbackFollowing && follow && !gestureRefocus.pending && !freezeWindow && !middleOwnsPosition && !isSeeking && !shortSeek.active && !autoRefocus.active && !controller.previewActive && controller.state == LyricLayerTransitionState.NORMAL) {
            val listState = activePane.listState
            val stepChange = activePane.playbackStep.consume(
                current,
                enabled = !dragging,
            )
            when (stepChange) {
                LyricPlaybackStepChange.NONE -> {
                    // 若交接结束后存在未同步的最新句，主动补位定位，避免遗漏
                    val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                    if (targetItem == null || !isLyricTargetTrulyVisible(listState, current)) {
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
                    val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                    if (targetItem != null) {
                        val travel = lyricVisibleSeekTravel(targetItem.offset)
                        runLyricNaturalPlaybackStep(activePane.playbackStep, current,
                            listState.layoutInfo.visibleItemsInfo.map { it.index }, travel,
                            { naturalStepFollowing && controller.currentPane === activePane }) { move ->
                            listState.scroll { move { scrollBy(it) } }
                        }
                    } else {
                        listState.animateScrollToItem(current)
                    }
                }
                LyricPlaybackStepChange.MULTI_STEP -> {
                    // 已测量的跨句沿用自然逐行位移；未测量目标复用屏外窗口准备。
                    val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                    if (targetItem != null) {
                        runLyricNaturalPlaybackStep(activePane.playbackStep, current,
                            listState.layoutInfo.visibleItemsInfo.map { it.index }, lyricVisibleSeekTravel(targetItem.offset),
                            { naturalStepFollowing && controller.currentPane === activePane }) { move ->
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

    BoxWithConstraints(modifier.widthIn(max = 760.dp)) {
        val anchor = lyricReadingAnchorDp(maxHeight.value, readingBounds, density,
            typography.estimatedRowHeightDp).dp
        val preparingHistory by remember(limitTransitionHistory, heldPreparation, transitionHistory) {
            derivedStateOf { limitTransitionHistory && !transitionHistory.preservingCloseWindow &&
                (heldPreparation != null || latestExitProgress() > 0f) }
        }
        val extraPaddingFraction = rememberLyricIntermediateSeekPaddingFraction(
            preparingHistory, middleOwnsPosition)
        // 寻焦落位前保留末尾行程，不能在松手收尾时同步压缩列表边界。
        val bottomPadding = (maxHeight - anchor).coerceAtLeast(72.dp) +
            anchor * extraPaddingFraction

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LyricWindowStages(controller, onDrawn = { currentDisplayPane ->
                    onWindowLayout?.let { record ->
                        val info = currentDisplayPane.listState.layoutInfo
                        record(transitionHistory.openingContext?.compactLayout ?: info,
                            lyricBlurProtection(info, currentDisplayPane.currentLine,
                            currentDisplayPane.playbackStep.offsetPx(currentDisplayPane.currentLine),
                            controller.state == LyricLayerTransitionState.NORMAL && !controller.previewActive))
                    }
                    if (currentDisplayPane.currentLine == current) heldPreparation?.drawn(current,
                        currentDisplayPane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }?.offset,
                        latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()),
                        controller.seekOffset.value, currentDisplayPane.listState.isScrollInProgress)
                    if (!currentDisplayPane.drawnFirstFrame &&
                        currentDisplayPane.id == controller.currentPane.id &&
                        currentDisplayPane.listState.layoutInfo.visibleItemsInfo.any { it.index == currentDisplayPane.initialLine }
                    ) {
                        currentDisplayPane.drawnFirstFrame = true
                        currentDisplayPane.ready.complete(Unit)
                    }
                    if (onReady != null && lyricWindowAligned(
                            currentDisplayPane.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }?.offset,
                            currentDisplayPane.listState.isScrollInProgress,
                            controller.seekOffset.value,
                        )
                    ) onReady()
                }) { currentDisplayPane, outgoing ->
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
                        boundaryWindow = boundaryWindow,
                        paneOffset = { controller.paneTranslation(currentDisplayPane) },
                        lyricSettleProgress = { if (outgoing) 1f else lyricSettle.value },
                        smoothProgressMs = if (outgoing) null else wordProgress,
                        onLineClick = lineClick@{ index, line ->
                            if (track.retryable) {
                                onRetryLyrics()
                                return@lineClick
                            }
                            middleSeek.cancel()
                            autoRefocus.cancel()
                            val fraction = lyricSeekFraction(line.timeMs, track.durationMs)
                            tapFocus.request(index, line.timeMs, (track.durationMs * fraction).toLong())
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
) {
    val latestPaneOffset by rememberUpdatedState(paneOffset)
    val lineWindow = remember(boundaryWindow) { boundaryWindow?.let { bounds ->
        LyricBoundaryWindow(bounds.topBuffer,
            offset = { bounds.offset() + latestPaneOffset() }, velocity = bounds.velocity)
    } }
    LazyColumn(
        state = pane.listState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = active && !pane.isFrozen,
        contentPadding = PaddingValues(top = anchor, bottom = bottomPadding),
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
                active = active && !pane.isFrozen,
                animateEmphasis = !pane.isFrozen && animateEmphasis,
                settleProgress = lyricSettleProgress,
                playbackMotion = pane.playbackStep,
                playbackStepActive = active && !pane.isFrozen && !isSeeking && !shortSeeking,
                smoothProgressMs = smoothProgressMs,
                onClick = { onLineClick(index, line) },
                boundary = lineWindow?.let { bounds -> LyricLineBoundary(bounds,
                    rowTop = {
                        val info = pane.listState.layoutInfo
                        info.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                            (it.offset - info.viewportStartOffset).toFloat()
                        }
                    }, viewportHeight = { pane.listState.layoutInfo.viewportSize.height.toFloat() }) },
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
