package com.musicone.demo

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    exitProgress: () -> Float = { 1f },
    currentAnchorFraction: Float? = null,
    seeking: () -> Boolean = { false },
    progressLyricSeek: ProgressLyricSeek? = null,
    onReady: (() -> Unit)? = null,
) {
    val lines = remember(track.id, track.lyrics) {
        track.lyrics.ifEmpty { listOf(TimedLyric(0L, "暂无歌词")) }
    }
    // 播放进度持续更新时，仅在跨过歌词时间点后才触发列表重组。
    val current by remember(lines, progressMs) {
        derivedStateOf { activeLyric(lines, lyricDisplayPosition(progressMs())) }
    }
    val isSeeking by remember(seeking) { derivedStateOf { seeking() } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val seekOffset = remember { Animatable(0f) }
    val lyricSettle = remember { Animatable(1f) }
    val lyricClock = LocalLyricAnimationClock.current
    val scope = rememberCoroutineScope { lyricClock ?: kotlin.coroutines.EmptyCoroutineContext }
    val playbackStep = remember(track.id) { LyricPlaybackStepMotion(current) }
    LyricAnimationEffect(playbackStep) { playbackStep.run() }
    var clickSeekJob by remember { mutableStateOf<Job?>(null) }
    var clickNavigation by remember { mutableStateOf(LyricClickNavigation.NONE) }
    var clickTarget by remember { mutableStateOf<Int?>(null) }
    var clickRevision by remember { mutableIntStateOf(0) }
    var frozenSeekSlot by remember { mutableStateOf<Int?>(null) }
    LyricAnimationEffect(clickNavigation, clickRevision) {
        if (clickNavigation != LyricClickNavigation.NONE) {
            // 极短时间戳或重复时间戳没有改变当前索引时，也必须解除点击事务。
            delay(1_500)
            clickNavigation = LyricClickNavigation.NONE
            clickTarget = null
            frozenSeekSlot = null
            lyricSettle.snapTo(1f)
        }
    }
    val seekLayers = listOf(rememberGraphicsLayer(), rememberGraphicsLayer())
    var seekSlot by remember { mutableIntStateOf(0) }
    var seekTravel by remember { mutableFloatStateOf(0f) }
    var seekCrossfade by remember { mutableStateOf(false) }
    var handledProgressLyricSeek by remember(track.id) {
        mutableLongStateOf(progressLyricSeek?.revision ?: 0L)
    }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(track.id) { mutableStateOf(true) }
    LyricAnimationEffect(dragging) {
        if (dragging) follow = false
        else if (!follow) {
            // 松手后的惯性仍属于手动浏览，从真正停稳后开始原有的回跟等待。
            snapshotFlow { list.isScrollInProgress }.first { !it }
            delay(3500)
            follow = true
        }
    }
    val latestExitProgress by rememberUpdatedState(exitProgress)
    LyricAnimationEffect(exitAlignment) {
        exitAlignment?.let { request ->
            // 只采集当前裁切量，滚动与残余窗口位移随后由父级收起曲线共同驱动。
            val initialSeekOffset = seekOffset.value
            list.alignForLyricsExit(request, { latestExitProgress() }) { fraction ->
                seekOffset.snapTo(initialSeekOffset * (1f - fraction))
            }
        }
    }
    BoxWithConstraints(modifier.widthIn(max = 760.dp)) {
        val largeText = maxWidth >= 400.dp
        val anchor = currentAnchorFraction
            ?.let { (maxHeight * it).coerceIn(96.dp, 180.dp) }
            ?: 52.dp
        val bottomPadding = (maxHeight - anchor).coerceAtLeast(72.dp)
        val anchorPx = with(androidx.compose.ui.platform.LocalDensity.current) { anchor.roundToPx() }
        LyricAnimationEffect(openingAlignment, prepareWhileHidden, if (prepareWhileHidden) current else null) {
            if (openingAlignment != null || prepareWhileHidden) {
                // 列表仍在屏幕外，先清除收起偏移并定位，随后只播放原有展开动画。
                clickSeekJob?.cancel()
                clickNavigation = LyricClickNavigation.NONE
                clickTarget = null
                frozenSeekSlot = null
                seekOffset.snapTo(0f)
                seekTravel = 0f
                seekCrossfade = false
                lyricSettle.snapTo(1f)
                playbackStep.reset(current)
                follow = true
                list.scrollToItem(current)
                openingAlignment?.complete(Unit)
            }
        }
        val pendingProgressSeek = progressLyricSeek?.takeIf {
            it.trackId == track.id && it.revision != handledProgressLyricSeek
        }
        val pendingProgressTarget = pendingProgressSeek?.let {
            activeLyric(lines, lyricDisplayPosition((track.durationMs * it.fraction).toLong()))
        }
        // 点击期间固定本次目标，播放回传和跨行更新不能中断正在进行的定位。
        val scrollTarget = clickTarget ?: pendingProgressTarget ?: current
        // 点击定位期间忽略短暂的预览状态切换，避免同一段位移动画被取消后重新播放。
        val seekingForAnimation = isSeeking && clickNavigation == LyricClickNavigation.NONE
        LyricAnimationEffect(track.id, scrollTarget, follow, followPlayback, anchorPx,
            seekingForAnimation, clickRevision, active, progressLyricSeek?.revision) {
            pendingProgressSeek?.let { request ->
                handledProgressLyricSeek = request.revision
                if (!active || !followPlayback) {
                    request.animationPrepared.complete(Unit)
                    return@LyricAnimationEffect
                }
                clickSeekJob?.cancel()
                val sourceIndex = activeLyric(lines, lyricDisplayPosition(request.fromPositionMs))
                val targetIndex = pendingProgressTarget ?: return@LyricAnimationEffect
                val visibleItems = list.layoutInfo.visibleItemsInfo
                val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
                val navigation = lyricClickNavigation(targetItem != null)
                follow = true
                val alreadySettled = targetIndex == sourceIndex &&
                    clickNavigation == LyricClickNavigation.NONE && abs(seekOffset.value) <= .5f
                if (alreadySettled) {
                    clickSeekJob = scope.launch {
                        lyricSettle.snapTo(0f)
                        lyricSettle.animateTo(
                            1f,
                            lyricPlaybackMotionSpec(0f, LyricPlaybackMotionPurpose.SEEK),
                        )
                    }
                } else {
                    frozenSeekSlot = seekSlot.takeIf { navigation != LyricClickNavigation.SCROLL }
                    clickTarget = targetIndex
                    clickNavigation = navigation
                    clickRevision++
                }
                request.animationPrepared.complete(Unit)
                return@LyricAnimationEffect
            }
            val clickMode = clickNavigation
            val stepChange = playbackStep.consume(scrollTarget,
                enabled = active && followPlayback && follow && !seekingForAnimation && !dragging &&
                    clickMode == LyricClickNavigation.NONE)
            if (stepChange != LyricPlaybackStepChange.ANIMATE) playbackStep.reset()
            // 顶部留白由 contentPadding 提供，滚动偏移不能再叠加一次。
            if ((follow || seekingForAnimation) && followPlayback) {
                if (clickMode == LyricClickNavigation.SCROLL) {
                    val targetItem = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == scrollTarget }
                    val travel = targetItem?.let { lyricVisibleSeekTravel(it.offset) } ?: 0f
                    coroutineScope {
                        // 中途改点上方时，残余图层位移与列表一起收敛，不能留在半程。
                        launch {
                            seekOffset.animateTo(
                                0f,
                                lyricPlaybackMotionSpec(travel, LyricPlaybackMotionPurpose.SEEK),
                            )
                        }
                        if (targetItem != null) {
                            list.animateScrollBy(
                                travel,
                                lyricPlaybackMotionSpec(travel, LyricPlaybackMotionPurpose.SEEK),
                            )
                        } else {
                            list.animateScrollToItem(scrollTarget)
                        }
                    }
                    clickNavigation = LyricClickNavigation.NONE
                    clickTarget = null
                    frozenSeekSlot = null
                    return@LyricAnimationEffect
                }
                when (if (clickMode == LyricClickNavigation.LAYERED) {
                    LyricScrollMode.CHANGE_WINDOW
                } else {
                    lyricScrollMode(seekingForAnimation, list.firstVisibleItemIndex, scrollTarget,
                        targetVisible = list.layoutInfo.visibleItemsInfo.any { it.index == scrollTarget })
                }) {
                    LyricScrollMode.SNAP -> {
                        // 进度条连续预览由手指直接驱动，不能把每次更新重播成整页入场。
                        seekOffset.snapTo(0f)
                        list.scrollToItem(scrollTarget)
                    }
                    LyricScrollMode.ANIMATE -> {
                        val targetItem = list.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == scrollTarget }
                        if (stepChange == LyricPlaybackStepChange.ANIMATE && targetItem != null) {
                            val indices = list.layoutInfo.visibleItemsInfo.map { it.index }
                            list.scroll {
                                playbackStep.move(scrollTarget, indices,
                                    lyricVisibleSeekTravel(targetItem.offset)) { scrollBy(it) }
                            }
                        } else {
                            if (stepChange == LyricPlaybackStepChange.ANIMATE) playbackStep.reset()
                            if (targetItem != null) {
                                val travel = lyricVisibleSeekTravel(targetItem.offset)
                                list.animateScrollBy(
                                    travel,
                                    lyricPlaybackMotionSpec(
                                        travel,
                                        LyricPlaybackMotionPurpose.ALIGNMENT,
                                    ),
                                )
                            } else {
                                list.animateScrollToItem(scrollTarget)
                            }
                        }
                    }
                    LyricScrollMode.CHANGE_WINDOW -> {
                        // 目标窗口先在不可见位置完成定位，再随同一段位移动画进入最终锚点。
                        val direction = if (scrollTarget > list.firstVisibleItemIndex) 1f else -1f
                        seekTravel = direction * list.layoutInfo.viewportSize.height
                        seekCrossfade = false
                        seekSlot = 1 - seekSlot
                        seekOffset.snapTo(seekTravel)
                        list.scrollToItem(scrollTarget)
                    }
                }
                val settleTravel = when (clickMode) {
                    LyricClickNavigation.LAYERED -> seekTravel
                    else -> seekOffset.value
                }
                seekOffset.animateTo(
                    0f,
                    lyricPlaybackMotionSpec(
                        settleTravel,
                        if (clickMode == LyricClickNavigation.NONE) {
                            LyricPlaybackMotionPurpose.ALIGNMENT
                        } else {
                            LyricPlaybackMotionPurpose.SEEK
                        },
                    ),
                )
                if (clickMode != LyricClickNavigation.NONE) {
                    clickNavigation = LyricClickNavigation.NONE
                    clickTarget = null
                    frozenSeekSlot = null
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 位移由独立图层处理，seek 的每个动画帧不再重新录制整窗歌词。
                Box(Modifier.matchParentSize().graphicsLayer {
                    translationY = seekOffset.value - seekTravel
                    alpha = lyricSeekLayerAlpha(
                        seekOffset.value,
                        seekTravel,
                        outgoing = true,
                        crossfade = seekCrossfade,
                    )
                }.drawWithContent { drawLayer(seekLayers[1 - seekSlot]) })
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        translationY = seekOffset.value
                        alpha = lyricSeekLayerAlpha(
                            seekOffset.value,
                            seekTravel,
                            outgoing = false,
                            crossfade = seekCrossfade,
                        )
                    }
                    .drawWithContent {
                        val currentLayer = seekLayers[seekSlot]
                        // 点击提交和列表换位发生在同一帧时，保留已经录好的来源层，禁止目标高亮覆盖它。
                        if (frozenSeekSlot != seekSlot) {
                            currentLayer.record { this@drawWithContent.drawContent() }
                        }
                        if (lyricWindowAligned(list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }?.offset,
                                list.isScrollInProgress, seekOffset.value)) onReady?.invoke()
                        drawLayer(currentLayer)
                    }, userScrollEnabled = active && followPlayback,
                    contentPadding = PaddingValues(top = anchor, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" },
                        contentType = { _, line -> line.translation != null }) { index, line ->
                        ImmersiveLyricLine(
                            line,
                            index,
                            current,
                            largeText,
                            active,
                            // 点击定位期间由整窗图层承载动效，行内属性不再单独争抢渲染预算。
                            animateEmphasis = clickNavigation == LyricClickNavigation.NONE ||
                                clickNavigation == LyricClickNavigation.SCROLL,
                            settleProgress = { lyricSettle.value },
                            playbackMotion = playbackStep,
                            playbackStepActive = active && follow && followPlayback && !isSeeking &&
                                clickNavigation == LyricClickNavigation.NONE,
                        ) {
                            clickSeekJob?.cancel()
                            val sameLine = index == current &&
                                clickNavigation == LyricClickNavigation.NONE && abs(seekOffset.value) <= .5f
                            val visibleItems = list.layoutInfo.visibleItemsInfo
                            val fraction = lyricSeekFraction(line.timeMs, track.durationMs)
                            val targetIndex = activeLyric(lines,
                                lyricDisplayPosition((track.durationMs * fraction).toLong()))
                            val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
                            val navigation = lyricClickNavigation(targetItem != null)
                            if (!sameLine) frozenSeekSlot =
                                seekSlot.takeIf { navigation != LyricClickNavigation.SCROLL }
                            clickSeekJob = scope.launch {
                                if (sameLine) lyricSettle.snapTo(0f) else {
                                    clickTarget = targetIndex
                                    clickNavigation = navigation
                                    clickRevision++
                                }
                                // 旧图层已经持续缓存，立即提交目标，避免额外保留一帧旧歌词。
                                onSeek(fraction)
                                if (sameLine) lyricSettle.animateTo(
                                    1f,
                                    lyricPlaybackMotionSpec(0f, LyricPlaybackMotionPurpose.SEEK),
                                )
                            }
                            follow = true
                        }
                    }
                }
            }
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

internal fun lyricScrollMode(seeking: Boolean, visibleIndex: Int, targetIndex: Int,
    targetVisible: Boolean = false): LyricScrollMode = when {
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
        // 前段自然露出被裁切行，后段继续整体退场，不先瞬移对齐再另起动画。
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
