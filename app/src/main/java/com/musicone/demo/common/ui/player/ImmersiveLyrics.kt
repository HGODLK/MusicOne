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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlin.math.abs

data class TimedLyric(val timeMs: Long, val text: String, val translation: String? = null)

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
            ?.takeIf { it.isNotBlank() && it != line.text }
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
    exitAlignment: CompletableDeferred<Unit>? = null,
    openingAlignment: CompletableDeferred<Unit>? = null,
    prepareWhileHidden: Boolean = false,
    revealTopLineBeforeEntrance: Boolean = false,
    limitTransitionHistory: Boolean = false,
    topExtensionPx: Int = 0,
    topBufferPx: Int = 0,
    exitProgress: () -> Float = { 1f },
    entranceProgress: (() -> Float)? = null,
    heldPreparation: LyricHeldWindowPreparation? = null,
    freezeWindow: Boolean = false,
    currentAnchorFraction: Float? = null,
    seeking: () -> Boolean = { false },
    progressLyricSeek: ProgressLyricSeek? = null,
    onReady: (() -> Unit)? = null,
    onWindowLayout: ((androidx.compose.foundation.lazy.LazyListLayoutInfo, LyricBlurProtection) -> Unit)? = null,
    onOpeningFront: ((PhoneLyricsOpeningFront) -> Unit)? = null,
) {
    val lines = remember(track.id, track.lyrics) {
        track.lyrics.ifEmpty { listOf(TimedLyric(0L, "暂无歌词")) }
    }
    val current by remember(lines, progressMs) {
        derivedStateOf { activeLyric(lines, lyricDisplayPosition(progressMs())) }
    }
    val isSeeking by remember(seeking) { derivedStateOf { seeking() } }
    val lyricClock = LocalLyricAnimationClock.current
    val scope = rememberCoroutineScope { lyricClock ?: kotlin.coroutines.EmptyCoroutineContext }
    val lyricSettle = remember { Animatable(1f) }

    val controller = remember(track.id) {
        LyricLayerTransitionController(track.id, current)
    }
    LaunchedEffect(current, isSeeking) {
        // 首个预览目标也由定位事务发布，不能先把旧列表的高亮改成目标句。
        controller.updatePlaybackLine(current, present = !isSeeking)
    }

    val activePane = controller.currentPane
    val shortSeek = remember(activePane) { LyricShortSeek() }
    val autoRefocus = remember(activePane) { LyricAutoRefocus() }
    DisposableEffect(autoRefocus) { onDispose { autoRefocus.cancel() } }
    DisposableEffect(shortSeek) { onDispose { shortSeek.cancel() } }
    val transitionHistory = remember(activePane) { LyricTransitionHistory() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val openingBaseSize = LocalPlayerLayoutSpec.current.lyricBaseSize
    LyricSeekPreviewEffect(controller, current, isSeeking, active && followPlayback)
    LyricAnimationEffect(activePane.playbackStep, freezeWindow) {
        if (!freezeWindow) activePane.playbackStep.run()
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
    var follow by remember(track.id) { mutableStateOf(true) }

    val latestExitProgress by rememberUpdatedState(exitProgress)
    val latestEntranceProgress by rememberUpdatedState(entranceProgress)
    LyricAnimationEffect(heldPreparation) {
        val request = heldPreparation ?: return@LyricAnimationEffect
        controller.cancel()
        lyricSettle.snapTo(1f)
        activePane.playbackStep.reset(current)
        follow = true
        topLinePreparationLoop(request, activePane.listState, transitionHistory, density,
            { activeLyric(lines, lyricDisplayPosition(progressMs())) },
            { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) })
    }
    val historyPhase by remember {
        derivedStateOf {
            val progress = 1f - latestExitProgress()
            when { progress <= 0f -> 0f; progress >= 1f -> 1f; else -> .5f }
        }
    }
    // 在绘制前发布完整段选择，不能等录制来源图层时才改变行的显示状态。
    if (!freezeWindow) transitionHistory.update({ activePane.listState.layoutInfo }, current, density, historyPhase,
        limitTransitionHistory && follow && !isSeeking && !shortSeek.active &&
            !controller.previewActive && controller.state == LyricLayerTransitionState.NORMAL)
    val topLineAlignment = rememberLyricTopLineAlignment(
        activePane.listState, revealTopLineBeforeEntrance, active, prepareWhileHidden,
        openingAlignment, exitAlignment, current,
        { latestEntranceProgress?.invoke() ?: (1f - latestExitProgress()) },
    )
    val playbackFollowing = followPlayback && (!revealTopLineBeforeEntrance || !topLineAlignment.pending)

    LyricAnimationEffect(activePane, dragging, isSeeking, freezeWindow, active, playbackFollowing,
        controller.state, controller.previewActive, shortSeek.active) {
        if (freezeWindow) return@LyricAnimationEffect
        if (isSeeking) {
            follow = true
        } else if (dragging) {
            follow = false
        } else if (!follow) {
            if (!active || !playbackFollowing || controller.previewActive ||
                controller.state != LyricLayerTransitionState.NORMAL || shortSeek.active) return@LyricAnimationEffect
            snapshotFlow { activePane.listState.isScrollInProgress }.first { !it }
            delay(3500)
            val targetIndex = activeLyric(lines, lyricDisplayPosition(progressMs()))
            val listState = activePane.listState
            val trulyVisible = isLyricTargetTrulyVisible(listState, targetIndex)
            val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, targetIndex)
            if (trulyVisible) {
                autoRefocus.align(activePane, { activeLyric(lines, lyricDisplayPosition(progressMs())) }) {
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

    LyricAnimationEffect(exitAlignment) {
        exitAlignment?.let { request ->
            activePane.listState.alignForLyricsExit(request, { latestExitProgress() }) { _ -> }
        }
    }

    val openingViewport by remember(activePane) {
        derivedStateOf { activePane.listState.layoutInfo.viewportSize }
    }
    LyricAnimationEffect(openingAlignment, prepareWhileHidden, if (prepareWhileHidden) current else null,
        onOpeningFront != null, if (prepareWhileHidden && onOpeningFront != null) openingViewport else null) {
        if (openingAlignment != null || prepareWhileHidden) {
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
                    activePane.listState.prepareOpeningContext(transitionHistory, current, density, topBufferPx)
                } else activePane.listState.revealTopLineForEntrance()
            }
            // 首开采用屏外准备后的实测行块，不能沿用上一帧绘制记录。
            onWindowLayout?.invoke(transitionHistory.openingContext?.compactLayout ?: activePane.listState.layoutInfo,
                lyricBlurProtection(activePane.listState.layoutInfo, current,
                    activePane.playbackStep.offsetPx(current), true))
            transitionHistory.openingContext?.let { context ->
                if (onOpeningFront != null) phoneLyricsOpeningFront(activePane.listState.layoutInfo, context,
                    current, activePane.playbackStep, density, openingBaseSize, lyricSettle.value)?.let(onOpeningFront)
            }
            openingAlignment?.complete(Unit)
        }
    }

    val pendingProgressSeek = progressLyricSeek?.takeIf {
        it.trackId == track.id && it.revision != handledProgressLyricSeek
    }

    LyricAnimationEffect(track.id, pendingProgressSeek?.revision, active, playbackFollowing, isSeeking, controller.previewActive) {
        pendingProgressSeek?.let { request ->
            autoRefocus.cancel()
            // 松手交接尚未结束的新单击稍后再处理，不能把它误当作拖动取消掉。
            if (!isSeeking && controller.previewActive) return@LyricAnimationEffect
            handledProgressLyricSeek = request.revision
            if (!active || !playbackFollowing || isSeeking) {
                request.sourceIsolated.complete(Unit)
                request.targetReady.complete(Unit)
                return@LyricAnimationEffect
            }
            val targetIndex = activeLyric(lines, lyricDisplayPosition((track.durationMs * request.fraction).toLong()))
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
    val naturalStepFollowing by rememberUpdatedState(active && playbackFollowing && follow && !dragging &&
        !freezeWindow && !isSeeking && !shortSeek.active && !autoRefocus.active && !controller.previewActive &&
        controller.state == LyricLayerTransitionState.NORMAL)
    LyricAnimationEffect(current, follow, playbackFollowing, active, isSeeking, controller.state, controller.previewActive, shortSeek.active, autoRefocus.active, freezeWindow) {
        if (active && playbackFollowing && follow && !freezeWindow && !isSeeking && !shortSeek.active && !autoRefocus.active && !controller.previewActive && controller.state == LyricLayerTransitionState.NORMAL) {
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
                    if (targetItem != null && isLyricTargetTrulyVisible(listState, current)) {
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
                    // 一次跨过多句：近距离滚动，远距离复用双列表图层交接
                    val trulyVisible = isLyricTargetTrulyVisible(listState, current)
                    val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, current)
                    if (trulyVisible || closeDistance) {
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
                LyricPlaybackStepChange.RESET -> {
                    activePane.playbackStep.reset(current)
                }
            }
        }
    }

    BoxWithConstraints(modifier.widthIn(max = 760.dp)) {
        val largeText = maxWidth >= 400.dp
        val baseAnchor = currentAnchorFraction
            ?.let { (maxHeight * it).coerceIn(96.dp, 180.dp) }
            ?: PLAYER_LYRICS_READING_ANCHOR
        val anchor = baseAnchor + with(androidx.compose.ui.platform.LocalDensity.current) {
            (topExtensionPx + topBufferPx).toDp()
        }
        val preparingHistory by remember(limitTransitionHistory, heldPreparation) {
            derivedStateOf { limitTransitionHistory && (heldPreparation != null || latestExitProgress() > 0f) }
        }
        // 屏外准备完整段落需要尾部行程；到展开端点时已回到原锚点和原留白。
        val bottomPadding = (maxHeight - anchor).coerceAtLeast(72.dp) +
            if (preparingHistory) anchor else 0.dp

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val outgoing = controller.outgoingPane
                if (outgoing != null) {
                    key(outgoing.id) {
                        val outgoingLayer = rememberGraphicsLayer()
                        Box(
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    if (controller.state == LyricLayerTransitionState.PREPARING) {
                                        // 准备交接：来源窗口位移固定为 0，透明度固定为 1，不套用滑动公式，避免空帧
                                        translationY = 0f
                                        alpha = 1f
                                    } else {
                                        translationY = controller.seekOffset.value - controller.seekTravel
                                        alpha = lyricSeekLayerAlpha(
                                            controller.seekOffset.value,
                                            controller.seekTravel,
                                            outgoing = true,
                                            crossfade = true,
                                        )
                                    }
                                }
                                .drawWithContent {
                                    outgoingLayer.record { this@drawWithContent.drawContent() }
                                    drawLayer(outgoingLayer)
                                },
                        ) {
                            LyricListView(
                                pane = outgoing,
                                lines = lines,
                                anchor = anchor,
                                bottomPadding = bottomPadding,
                                largeText = largeText,
                                active = false,
                                isSeeking = false,
                                lyricSettleProgress = { 1f },
                                onLineClick = { _, _ -> },
                            )
                        }
                    }
                }

                val currentDisplayPane = controller.currentPane
                key(currentDisplayPane.id) {
                    val activeLayer = rememberGraphicsLayer()
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                if (controller.state == LyricLayerTransitionState.PREPARING) {
                                    // 准备交接：目标层保持不可见，在屏外静默完成布局和录制
                                    translationY = 0f
                                    alpha = 0f
                                } else {
                                    translationY = controller.seekOffset.value
                                    alpha = lyricSeekLayerAlpha(
                                        controller.seekOffset.value,
                                        controller.seekTravel,
                                        outgoing = false,
                                        crossfade = true,
                                    )
                                }
                            }
                            .drawWithContent {
                                activeLayer.record { this@drawWithContent.drawContent() }
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
                                drawLayer(activeLayer)
                            },
                    ) {
                        LyricListView(
                            pane = currentDisplayPane,
                            lines = lines,
                            anchor = anchor,
                            bottomPadding = bottomPadding,
                            largeText = largeText,
                            active = active && playbackFollowing,
                            isSeeking = isSeeking,
                            shortSeeking = shortSeek.active,
                            transitionHistory = if (limitTransitionHistory) transitionHistory else null,
                            lyricSettleProgress = { lyricSettle.value },
                            onLineClick = { index, line ->
                                autoRefocus.cancel()
                                val fraction = lyricSeekFraction(line.timeMs, track.durationMs)
                                val listState = currentDisplayPane.listState
                                val trulyVisible = isLyricTargetTrulyVisible(listState, index)
                                val closeDistance = shouldAnimateLyricScroll(listState.firstVisibleItemIndex, index)
                                follow = true
                                if (trulyVisible || closeDistance) {
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
                }
                LyricSeekPreviewTarget(controller, lines, anchor, bottomPadding, largeText)
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
    largeText: Boolean,
    active: Boolean,
    isSeeking: Boolean,
    lyricSettleProgress: () -> Float,
    onLineClick: (Int, TimedLyric) -> Unit,
    modifier: Modifier = Modifier,
    shortSeeking: Boolean = false,
    transitionHistory: LyricTransitionHistory? = null,
) {
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
                largeText = largeText,
                active = active && !pane.isFrozen,
                animateEmphasis = !pane.isFrozen,
                settleProgress = lyricSettleProgress,
                playbackMotion = pane.playbackStep,
                playbackStepActive = active && !pane.isFrozen && !isSeeking && !shortSeeking,
                onClick = { onLineClick(index, line) },
                modifier = if (transitionHistory == null) Modifier else Modifier.drawWithContent {
                    // 仅跳过过量的完整历史段，原文、翻译和图层位移都不裁剪。
                    if (transitionHistory.draws(index)) drawContent()
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
