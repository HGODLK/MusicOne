package com.musicone

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun PlayerSyncedLyricsContent(
    track: MusicTrack,
    loadState: LyricLoadState,
    viewModel: MusicPlaybackRuntime,
    modifier: Modifier,
    active: Boolean = true,
    followPlayback: Boolean = active,
    animateTrackChange: Boolean = followPlayback,
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
    heldTrackChange: Boolean = false,
    intermediateSeek: Boolean = false,
    lyricsGestureHeld: Boolean = false,
    closingLyricsDrag: Boolean = false,
    readingBounds: LyricReadingBounds? = null,
    boundaryWindow: LyricBoundaryWindow? = null,
    trackTransitionDirection: TrackTransitionDirection = TrackTransitionDirection.NEXT,
    onWindowLayout: ((androidx.compose.foundation.lazy.LazyListLayoutInfo, LyricBlurProtection) -> Unit)? = null,
) {
    val request by rememberLyricPresentationRequest(
        track, loadState, trackTransitionDirection, viewModel.rapidTrackSwitch, viewModel.state,
    )
    val playbackProgress = viewModel.playbackProgress.collectAsStateWithLifecycle()
    val playbackActivity = viewModel.playbackActivity.collectAsStateWithLifecycle()
    val preview = viewModel.seekPreview.position.collectAsStateWithLifecycle()
    val progressLyricSeek = viewModel.seekPreview.progressLyricSeek.collectAsStateWithLifecycle()
    val progressCommit = viewModel.seekPreview.progressCommit.collectAsStateWithLifecycle()
    val lyricSeek = viewModel.seekPreview.lyricSeek.collectAsStateWithLifecycle()
    var current by remember {
        mutableStateOf(request.presentation?.let {
            LyricWindow(it).also { window ->
                window.playback.update(viewModel.playbackProgress.value, viewModel.seekPreview.position.value,
                    browsing = viewModel.rapidTrackSwitch.presentation.value != null)
            }
        })
    }
    val outgoing = remember { mutableStateListOf<LyricWindow>() }
    val motion = remember { LyricWindowMotion() }
    val held = remember { LyricHeldTrackTransition() }
    val drawEpoch = LocalPlayerLyricsContent.current?.drawEpoch
    val residentStandby = LocalPlayerLyricsContent.current?.residentStandby
    val phonePreparation = LocalPhonePlayerPresentation.current?.preparation
    val phoneLyricsMotion = LocalRetainedPhoneLyricsMotion.current
    val parentDeadline = remember(phonePreparation, phoneLyricsMotion) { {
        minOf(phonePreparation?.deadlineNanos ?: Long.MAX_VALUE,
            phoneLyricsMotion?.openingDeadlineNanos ?: Long.MAX_VALUE)
    } }
    val lyricClock = LocalLyricAnimationClock.current
    val heldScope = rememberCoroutineScope { lyricClock ?: kotlin.coroutines.EmptyCoroutineContext }
    val latestHeldTrackChange by rememberUpdatedState(heldTrackChange)
    val latestIntermediateSeek by rememberUpdatedState(intermediateSeek)
    val latestActive by rememberUpdatedState(active)
    val pageMoving = LocalPlayerLyricsMoving.current
    val turn = LocalPhonePlayerTurn.current
    val turnMoving by remember(turn) { derivedStateOf {
        turn?.hasVisibleMotion(PhonePlayerPresentation.BodyKeys) == true
    } }
    val latestMoving by rememberUpdatedState(pageMoving || turnMoving)
    val lyricsDisplayed = LocalPlayerLyricsDisplayed.current
    val latestDisplayed by rememberUpdatedState(active || lyricsDisplayed || heldTrackChange)
    DisposableEffect(held) { onDispose { held.cancel(); held.clearSlides() } }
    val view = LocalView.current
    var travelPx by remember { mutableFloatStateOf(0f) }
    var preparing by remember { mutableStateOf(false) }
    val requested by rememberUpdatedState(request.presentation)
    val direction by rememberUpdatedState(request.direction)
    val animateChange = active && animateTrackChange
    val latestAnimateChange by rememberUpdatedState(animateChange)
    val rapid by rememberRapidLyricHandoff(viewModel.rapidTrackSwitch, animateChange) { pending ->
        val window = current
        !preparing && (window == null || motion.settledAt(window.slot)) &&
            (!pending.lyricsAvailable || (window?.presentation?.id == pending.trackId &&
                window.presentation.source == pending.source && window.playback.positionMs == 0L))
    }
    LyricAnimationEffect(current, request.trackId, rapid != null) {
        val window = current?.takeIf { it.presentation.id == request.trackId }
            ?: return@LyricAnimationEffect
        snapshotFlow { Triple(Triple(playbackProgress.value, preview.value, progressCommit.value),
            latestIntermediateSeek || window.playback.intermediatePositionOwned,
            latestActive) }.collect { (progress, intermediate, windowActive) ->
            val (snapshot, previewMs, commit) = progress
            window.playback.updateObserved(snapshot, previewMs, browsing = rapid != null,
                latest = viewModel.playbackProgress.value,
                latestPreviewMs = viewModel.seekPreview.position.value,
                latestBrowsing = viewModel.rapidTrackSwitch.presentation.value != null,
                intermediateSeek = intermediate, active = windowActive,
                observedCommit = commit, latestCommit = viewModel.seekPreview.progressCommit.value)
        }
    }
    // 页面显隐及方向变化不能重启跨曲事务；只在真正收到下一首请求时选择表现方式。
    LyricAnimationEffect(Unit) {
        outgoing.forEach {
            it.cleanup?.cancel()
        }
        outgoing.clear()
        held.clearSlides()
        preparing = false
        if (!latestAnimateChange && !held.active) motion.show(current?.slot ?: 0f)
        // 最新目标可取消仍在等待的准备，运动与旧页退出任务仍属于外层作用域。
        snapshotFlow {
            Triple(requested, direction to (rapid?.phase == RapidTrackSwitchPhase.BROWSING),
                playbackProgress.value.generation)
        }.collectLatest {
            // 不可见歌词的新窗口避开开合和转向；重新可见立即准备，并只读取最后一首。
            if (deferHiddenLyricTrackChange(latestMoving, latestDisplayed)) {
                snapshotFlow { !deferHiddenLyricTrackChange(latestMoving, latestDisplayed) }.first { it }
            }
            // 通知只负责唤醒：旧请求与新轮次不能拼成一次虚假的切歌。
            val pending = viewModel.rapidTrackSwitch.presentation.value
            val latest = currentLyricPresentationRequest(viewModel.state.value, pending) ?: return@collectLatest
            val target = latest.presentation
            val travelDirection = latest.direction
            val browsing = pending?.phase == RapidTrackSwitchPhase.BROWSING
            val progress = viewModel.playbackProgress.value
            val generation = progress.generation
            val animateChange = latestAnimateChange
            if (preparing && current?.presentation != target) {
                // 尚未入场的旧目标不算来源；包括最新歌曲还没有缓存歌词的情况。
                // 先接回真实画面，再处理新请求，不能留下永久隐藏的 current。
                val displayed = outgoing.lastOrNull()
                displayed?.cleanup?.cancelAndJoin()
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    current = displayed
                    if (displayed != null) outgoing.remove(displayed)
                    preparing = false
                }
            }
            if (target == null) {
                // 无本地歌词的目标不造空白页，让已有窗口停止连续追踪后正常收束。
                if (animateChange && !held.active) current?.let { motion.request(this, it.slot) }
                return@collectLatest
            }
            if (!preparing && target.source == current?.presentation?.source && target.id == current?.presentation?.id &&
                (pending == null || current?.playback?.positionMs == 0L) &&
                (current?.playback?.generation == generation || current?.playback?.generation == -1L)) {
                // 同一首中间歌曲的本地歌词稍后命中时，只替换内容，不重播整窗切歌动画。
                current?.presentation = target
                if (animateChange && !held.active) current?.let { motion.request(this, it.slot, browsing) }
                return@collectLatest
            }
            if (pending == null && !held.active && !preparing && current?.let { motion.settledAt(it.slot) } == true) {
                current?.refreshPlaybackRound(target, progress)?.let { refreshed ->
                    current = refreshed
                    return@collectLatest
                }
            }
            // 本次渐变锁定到完成；外层停稳不会把同一首再补播一次滑动。
            if (lyricTrackUsesFade(latestMoving, latestHeldTrackChange, held.active, latestDisplayed)) {
                val source = current
                val gesturePreparation = latestHeldTrackChange
                val initialSources = if (held.active) emptyList() else (outgoing.toList() + listOfNotNull(source))
                    .distinct().filter { !(it === source && preparing) &&
                        kotlin.math.abs(motion.offset.value + it.slot) < 1f }
                    .map { LyricHeldBlend(it, held.slidingWeight(it) ?: 1f) }
                val visibleSources = if (held.active) held.layers.filter { it.weight > 0f }.map { it.window }
                    else initialSources.filter { it.weight > 0f }.map { it.window }
                visibleSources.forEach { old ->
                    old.freezeDisplayedContent?.invoke()
                    if (old.frozenTrackTranslation == null) old.frozenTrackTranslation = lyricTrackWindowTranslation(
                        lyricTrackWindowStageRole(old, current, outgoing, held), preparing, held.active,
                        travelPx, motion.offset.value, old.slot)
                    old.cleanup?.cancel()
                }
                val incoming = LyricWindow(target, if (gesturePreparation) source?.slot ?: 0f else 0f)
                incoming.playback.update(progress, viewModel.seekPreview.position.value, browsing = pending != null)
                held.request(source?.takeUnless { preparing }, incoming, heldScope, gesturePreparation, initialSources,
                    parentDeadline = parentDeadline(), drawEpoch = drawEpoch)
                current = incoming
                preparing = false
                outgoing.clear()
                held.clearSlides()
                // 来源已保存真实位移，只有新的歌词窗口使用正常阅读坐标。
                motion.show(incoming.slot)
                return@collectLatest
            }
            val released = held.releaseForSlide()
            released.filter { it !in outgoing }.forEach(outgoing::add)
            // 取消等待只淘汰隐藏目标，来源仍取正在退场的最后一个真实窗口。
            val pendingWindow = current?.takeIf { preparing && it.presentation == target }
            val previous = (released.lastOrNull() ?: if (preparing) outgoing.lastOrNull() else current)
                .takeIf { animateChange }
            // 快速切回时接回仍在退场的原窗口，保留其列表锚点、高亮与真实运动位置。
            val returning = outgoing.lastOrNull {
                previous != null && held.slidingWeight(it) == null &&
                    canReturnLyricWindow(it.slot, previous.slot, motion.offset.value, travelDirection) &&
                    it.canReturnTo(target, progress, restarting = pending != null)
            }
            returning?.cleanup?.cancelAndJoin()
            if (returning != null) outgoing.remove(returning)
            val incoming = returning?.also { it.presentation = target }
                ?: pendingWindow ?: LyricWindow(target, if (animateChange) {
                    nextLyricWindowSlot(previous?.slot ?: -motion.offset.value, motion.offset.value, travelDirection, browsing)
                } else 0f)
            incoming.playback.update(progress, viewModel.seekPreview.position.value, browsing = pending != null)
            outgoing.filter { it.slot == incoming.slot }.forEach {
                it.cleanup?.cancelAndJoin()
                outgoing.remove(it)
                held.forgetSlide(it)
            }
            // 屏外待播页被替换时不加入退场列表，避免积累不可见歌词和同位置副本。
            previous?.takeIf { it.slot != incoming.slot && it !in outgoing }?.let(outgoing::add)
            current = incoming
            preparing = animateChange && returning == null
            if (animateChange) {
                if (returning != null) withFrameNanos { }
                else if (!incoming.preparationBudget.await(incoming.ready, parentDeadline())) {
                    // collectLatest 已取消过期请求；超时只恢复仍由本事务持有的目标。
                    if (current !== incoming) return@collectLatest
                    preparing = false
                    recoverLyricWindow(incoming, outgoing.toList(), motion, held, heldScope, drawEpoch, travelPx)
                    outgoing.clear()
                    return@collectLatest
                }
                preparing = false
                playbackTrace("MusicOne:lyric-enter-request") { motion.request(this, incoming.slot, browsing) }
                outgoing.toList().forEach { old ->
                    old.cleanup?.cancelAndJoin()
                    val exitDirection = if (old.slot < incoming.slot) TrackTransitionDirection.NEXT
                        else TrackTransitionDirection.PREVIOUS
                    // 全部窗口共用位移，快速连点和反向也不会让两页文字互相追越。
                    old.cleanup = launch {
                        snapshotFlow { lyricWindowOutside(motion.offset.value + old.slot, exitDirection) }
                            .first { it }
                        // 完全滑出后再错开数帧释放整棵歌词列表，避免收尾帧同时承担销毁工作。
                        kotlinx.coroutines.delay(64)
                        playbackTrace("MusicOne:lyric-dispose-request") {
                            outgoing.remove(old)
                            held.forgetSlide(old)
                        }
                    }
                }
            } else {
                motion.show(incoming.slot)
                outgoing.clear()
                held.clearSlides()
            }
        }
    }
    // 播放跟随可以先停，歌词仍在开合或转向时必须继续提交实际绘制帧。
    BoxWithConstraints(modifier.lyricWindowFrameDrive(current != null &&
        (active || lyricsDisplayed || pageMoving || turnMoving || heldTrackChange))
        .playerLyricsRegion(active, topExtensionPx).onGloballyPositioned {
        travelPx = lyricWindowTravelPx(it.positionInWindow().y, it.size.height.toFloat(), view.rootView.height.toFloat())
        motion.updateTravel(travelPx)
    }.lyricHeldComposite { held.active || held.hasSlidingBlend }) {
        val layoutMode = LocalPlayerLayoutSpec.current.mode
        val touchPadding = LocalLandscapeLyricsTouchPadding.current
        val column = LocalPhoneLyricsColumnWidth.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val columnDp = column?.let { it / density.density }
        val typography = remember(layoutMode, maxWidth, touchPadding, columnDp) {
            lyricTypographyForWidth(layoutMode, minOf(columnDp ?: (maxWidth - touchPadding).value, 760f))
        }
        val windows = if (held.active) held.layers.map { it.window } else outgoing.toList() + listOfNotNull(current)
        windows.forEach { window ->
            key(window) {
                val shown = window.presentation
                val old = window !== current
                val heldPreparing = !old && held.active && held.preparation != null
                val fadePreparing = !old && held.active && held.awaitingLayout && held.preparation == null
                val windowPreparing = preparing || heldPreparing || fadePreparing
                val fallbackDisplay = window.displayFallback
                val localReadiness = remember(window) { LyricLayoutReadiness() }
                val frozen = old && (held.active || held.slidingWeight(window) != null)
                val frozenLayout = remember(window) { LyricFrozenWindowLayout() }
                val liveGeometry = LyricWindowGeometry(typography, readingBounds, boundaryWindow,
                    touchPadding, topExtensionPx, topBufferPx, LocalContentColor.current,
                    exitProgress, entranceProgress, limitTransitionHistory)
                val geometry = frozenLayout.geometry(liveGeometry, frozen)
                SideEffect { if (!frozen) frozenLayout.record(liveGeometry) }
                val stageRole = remember(window, held) {
                    { lyricTrackWindowStageRole(window, current, outgoing, held) }
                }
                val matchesPlayback = shown.id == track.id && shown.source == track.source
                val settled by remember(window, motion) {
                    derivedStateOf { motion.settledAt(window.slot) }
                }
                val windowSeeking = remember(window, old, matchesPlayback, preview) {
                    { preview.value != null && !old && matchesPlayback }
                }
                val advancing = playbackActivity.value.advancing && playbackActivity.value.trackId == shown.id &&
                    playbackProgress.value.generation == window.playback.generation && !old && matchesPlayback &&
                    rapid == null && !window.playback.intermediateCommitPending && !window.playback.intermediatePositionOwned
                val latestAdvancing by rememberUpdatedState(advancing)
                val continuousPlayback = (active || residency.keepRefreshing) && !old && !windowPreparing && settled
                val playbackClock = rememberContinuousLyricClock(
                    { window.playback.wordSnapshot(playbackProgress.value, if (old) null else preview.value).positionMs },
                    { window.playback.wordSnapshot(playbackProgress.value, if (old) null else preview.value).sampledAtMs },
                    { latestAdvancing }, windowSeeking, shown.durationMs,
                    continuousPlayback,
                    resetKey = listOf(window.playback.generation,
                        lyricSeek.value?.takeIf { it.trackId == shown.id }?.revision,
                        progressLyricSeek.value?.takeIf { it.trackId == shown.id }?.revision,
                        progressCommit.value?.takeIf { it.trackId == shown.id }?.revision))
                // 整行与逐字共用同一采样基准，换句不再等待下一次整秒轮询。
                val windowProgress = remember(window, playbackClock, continuousPlayback) {
                    { if (continuousPlayback) playbackClock() else window.playback.positionMs }
                }
                CompositionLocalProvider(LocalLandscapeLyricsTouchPadding provides geometry.touchPadding,
                    LocalContentColor provides geometry.contentColor) {
                ImmersiveLyrics(
                    shown,
                    windowProgress,
                    { fraction ->
                        viewModel.seekPreview.requestLyricSeek(track.id, fraction)
                        viewModel.seekTo(fraction)
                    },
                    Modifier.fillMaxSize().lyricHeldWeight {
                        if (held.active) held.weight(window) else held.slidingWeight(window)
                    }.lyricTrackWindowStage(motion, held, window, stageRole,
                        preparing = { preparing }, heldTrackChange = { latestHeldTrackChange },
                        travelPx = { travelPx }).lyricFrozenWindowLayout(frozenLayout, frozen),
                    active = active && !old && matchesPlayback && !windowPreparing && settled,
                    followPlayback = followPlayback && !old && matchesPlayback && !windowPreparing && settled && !held.active,
                    verbatimProgressMs = if (residency.pauseWords) null else playbackClock,
                    onRetryLyrics = viewModel::retryLyrics,
                    registerTrackFreeze = { window.freezeDisplayedContent = it },
                    layoutReadiness = if (!old && turn != null &&
                        (turn.preparing || phonePreparation?.preparing == true || openingAlignment != null))
                        LocalPhonePlayerPresentation.current?.lyricsLayout
                        else if (!old && (windowPreparing || fallbackDisplay && !window.ready.isCompleted)) localReadiness else null,
                    displayRequest = window.ready,
                    displayFallback = fallbackDisplay,
                    readingAnchor = if (!old && turn != null)
                        LocalPhonePlayerPresentation.current?.lyricsAnchor else null,
                    tapFocusProgressMs = {
                        val snapshot = viewModel.playbackProgress.value
                        continuousLyricPosition(snapshot.positionMs, snapshot.sampledAtMs,
                            android.os.SystemClock.elapsedRealtime(), latestAdvancing, windowSeeking(), shown.durationMs)
                    },
                    residency = if (!old && matchesPlayback && !windowPreparing && settled) residency else LyricResidency.None,
                    onReady = if (!old && (preparing || fallbackDisplay || held.active && held.awaitingLayout && held.preparation == null)) { {
                        if (stageRole() == LyricWindowStageRole.CURRENT &&
                            (preparing && !held.active || fallbackDisplay || held.active && held.awaitingLayout && held.preparation == null)) {
                            window.ready.complete(Unit)
                        }
                        Unit
                    } } else null,
                    exitAlignment = if (old) null else exitAlignment,
                    openingAlignment = if (old || !matchesPlayback) null else openingAlignment,
                    residentStandby = residentStandby.takeIf { !old && matchesPlayback && !held.active },
                    // 转向中渐变的零权重目标也必须主动定位，不能关闭跟随后只等待一份永远不到的就绪。
                    prepareWhileHidden = !old && !fallbackDisplay && (fadePreparing || !held.active && (preparing || (prepareWhileHidden && matchesPlayback))),
                    // 切歌窗口仍按原锚点就绪；完整顶行仅用于单栏歌词开关入场。
                    revealTopLineBeforeEntrance = revealTopLineBeforeEntrance && !old && !preparing && !fadePreparing,
                    limitTransitionHistory = geometry.limitTransitionHistory && (!old || held.active) && !preparing && !fadePreparing,
                    heldPreparation = if (heldPreparing) held.preparation else null,
                    // 菜单方向锁和普通手势也使用 isTurning，不能因此暂停歌词。
                    freezeWindow = frozen || turn != null &&
                        (!fadePreparing && phonePreparation?.preparing == true &&
                            LocalPhonePlayerPresentation.current?.lyricsAnchor?.held == true || openingAlignment != null),
                    topExtensionPx = geometry.topExtension,
                    topBufferPx = geometry.topBuffer,
                    exitProgress = geometry.exitProgress ?: exitProgress,
                    entranceProgress = geometry.entranceProgress,
                    readingBounds = geometry.readingBounds,
                    boundaryWindow = geometry.boundaryWindow?.let { bounds ->
                        LyricBoundaryWindow(bounds.topBuffer,
                            offset = { bounds.offset() + (window.frozenTrackTranslation ?: lyricTrackWindowTranslation(stageRole(), preparing,
                                held.active, travelPx, motion.offset.value, window.slot)) },
                            velocity = { bounds.velocity() }, edges = bounds.edges, partialTop = bounds.partialTop)
                    },
                    typography = geometry.typography,
                    seeking = windowSeeking,
                    intermediateSeek = intermediateSeek && !old && matchesPlayback && !windowPreparing && settled && !held.active,
                    lyricsGestureHeld = lyricsGestureHeld,
                    closingLyricsDrag = closingLyricsDrag,
                    progressLyricSeek = if (!old && matchesPlayback) window.playback.progressSeek(progressLyricSeek.value) else null,
                    intermediateCommit = if (!old && matchesPlayback) window.playback.intermediateCommit else null,
                    intermediateCommitPending = !old && matchesPlayback && window.playback.intermediateCommitPending,
                    onIntermediateSeekOwnership = { window.playback.intermediatePositionOwned = it },
                    onWindowLayout = if (!old && matchesPlayback && !held.active && onWindowLayout != null) {
                        { layout, protection ->
                            if (stageRole() == LyricWindowStageRole.CURRENT && !held.active) {
                                onWindowLayout(layout,
                                    if (preparing || !settled || outgoing.isNotEmpty()) LyricBlurProtection.HANDOFF else protection)
                            }
                        }
                    } else null,
                )
                }
            }
        }
        if (LocalPlayerPrewarming.current) {
            LyricRenderWarmup(current?.presentation, current?.playback?.positionMs ?: 0L, typography)
        }
        if (current == null) LyricAnimationEffect(exitAlignment) { exitAlignment?.complete(Unit) }
        if (current == null) LyricAnimationEffect(openingAlignment) { openingAlignment?.complete(Unit) }
    }
}

internal fun lyricTrackEnterOffset(fullHeight: Int, direction: TrackTransitionDirection): Int =
    if (direction == TrackTransitionDirection.NEXT) fullHeight else -fullHeight

internal fun lyricTrackExitOffset(fullHeight: Int, direction: TrackTransitionDirection): Int =
    -lyricTrackEnterOffset(fullHeight, direction)
