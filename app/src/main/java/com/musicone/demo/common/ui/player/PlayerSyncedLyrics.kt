package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun PlayerSyncedLyrics(
    track: MusicTrack,
    loadState: LyricLoadState,
    viewModel: MusicOneViewModel,
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
    heldTrackChange: Boolean = false,
    currentAnchorFraction: Float? = null,
    trackTransitionDirection: TrackTransitionDirection = TrackTransitionDirection.NEXT,
    onWindowLayout: ((androidx.compose.foundation.lazy.LazyListLayoutInfo, LyricBlurProtection) -> Unit)? = null,
    onOpeningFront: ((PhoneLyricsOpeningFront) -> Unit)? = null,
) {
    val request by rememberLyricPresentationRequest(
        track, loadState, trackTransitionDirection, viewModel.rapidTrackSwitch, viewModel.state,
    )
    val playbackProgress = viewModel.playbackProgress.collectAsStateWithLifecycle()
    val preview = viewModel.seekPreview.position.collectAsStateWithLifecycle()
    val progressLyricSeek = viewModel.seekPreview.progressLyricSeek.collectAsStateWithLifecycle()
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
    val lyricClock = LocalLyricAnimationClock.current
    val heldScope = rememberCoroutineScope { lyricClock ?: kotlin.coroutines.EmptyCoroutineContext }
    val latestHeldTrackChange by rememberUpdatedState(heldTrackChange)
    DisposableEffect(held) { onDispose { held.cancel() } }
    val view = LocalView.current
    var travelPx by remember { mutableFloatStateOf(0f) }
    var preparing by remember { mutableStateOf(false) }
    val requested by rememberUpdatedState(request.presentation)
    val direction by rememberUpdatedState(request.direction)
    val animateChange = active && followPlayback
    val rapid by rememberRapidLyricHandoff(viewModel.rapidTrackSwitch, animateChange) { pending ->
        val window = current
        !preparing && (window == null || motion.settledAt(window.slot)) &&
            (!pending.lyricsAvailable || (window?.presentation?.id == pending.trackId &&
                window.presentation.source == pending.source && window.playback.positionMs == 0L))
    }
    LyricAnimationEffect(current, request.trackId, rapid != null) {
        val window = current?.takeIf { it.presentation.id == request.trackId }
            ?: return@LyricAnimationEffect
        snapshotFlow { playbackProgress.value to preview.value }.collect { (snapshot, previewMs) ->
            window.playback.updateObserved(snapshot, previewMs, browsing = rapid != null,
                latest = viewModel.playbackProgress.value,
                latestPreviewMs = viewModel.seekPreview.position.value,
                latestBrowsing = viewModel.rapidTrackSwitch.presentation.value != null)
        }
    }
    LyricAnimationEffect(animateChange) {
        outgoing.forEach {
            it.cleanup?.cancel()
        }
        outgoing.clear()
        preparing = false
        if (!animateChange && !held.active) motion.show(current?.slot ?: 0f)
        // 新目标就绪后即可交接；旧窗口在后台完整滑出，等待期间只保留最新请求。
        snapshotFlow {
            Triple(requested, direction to (rapid?.phase == RapidTrackSwitchPhase.BROWSING),
                playbackProgress.value.generation)
        }.conflate().collect {
            // 通知只负责唤醒：旧请求与新轮次不能拼成一次虚假的切歌。
            val pending = viewModel.rapidTrackSwitch.presentation.value
            val latest = currentLyricPresentationRequest(viewModel.state.value, pending) ?: return@collect
            val target = latest.presentation
            val travelDirection = latest.direction
            val browsing = pending?.phase == RapidTrackSwitchPhase.BROWSING
            val progress = viewModel.playbackProgress.value
            val generation = progress.generation
            if (target == null) {
                // 无本地歌词的目标不造空白页，让已有窗口停止连续追踪后正常收束。
                if (animateChange && !held.active) current?.let { motion.request(this, it.slot) }
                return@collect
            }
            if (target.source == current?.presentation?.source && target.id == current?.presentation?.id &&
                (pending == null || current?.playback?.positionMs == 0L) &&
                (current?.playback?.generation == generation || current?.playback?.generation == -1L)) {
                // 同一首中间歌曲的本地歌词稍后命中时，只替换内容，不重播整窗切歌动画。
                current?.presentation = target
                if (animateChange && !held.active) current?.let { motion.request(this, it.slot, browsing) }
                return@collect
            }
            // 只有按住中间态发起的跨曲交接走原位渐变；松手后完成已有事务。
            if (latestHeldTrackChange || held.active) {
                val source = current
                val incoming = LyricWindow(target, source?.slot ?: 0f)
                incoming.playback.update(progress, viewModel.seekPreview.position.value, browsing = pending != null)
                held.request(source, incoming, heldScope)
                current = incoming
                preparing = false
                return@collect
            }
            val previous = current.takeIf { animateChange }
            // 快速切回时接回仍在退场的原窗口，保留其列表锚点、高亮与真实运动位置。
            val returning = outgoing.lastOrNull {
                previous != null && canReturnLyricWindow(it.slot, previous.slot, motion.offset.value, travelDirection) &&
                    it.canReturnTo(target, progress, restarting = pending != null)
            }
            returning?.cleanup?.cancelAndJoin()
            if (returning != null) outgoing.remove(returning)
            val incoming = returning?.also { it.presentation = target }
                ?: LyricWindow(target, if (animateChange) {
                    nextLyricWindowSlot(previous?.slot ?: -motion.offset.value, motion.offset.value, travelDirection, browsing)
                } else 0f)
            incoming.playback.update(progress, viewModel.seekPreview.position.value, browsing = pending != null)
            outgoing.filter { it.slot == incoming.slot }.forEach {
                it.cleanup?.cancelAndJoin()
                outgoing.remove(it)
            }
            // 屏外待播页被替换时不加入退场列表，避免积累不可见歌词和同位置副本。
            previous?.takeIf { it.slot != incoming.slot && it !in outgoing }?.let(outgoing::add)
            current = incoming
            preparing = animateChange && returning == null
            if (animateChange) {
                if (returning != null) withFrameNanos { } else incoming.ready.await()
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
                        playbackTrace("MusicOne:lyric-dispose-request") { outgoing.remove(old) }
                    }
                }
            } else {
                motion.show(incoming.slot)
                outgoing.clear()
            }
        }
    }
    Box(modifier.playerLyricsRegion(active, topExtensionPx).onGloballyPositioned {
        travelPx = lyricWindowTravelPx(it.positionInWindow().y, it.size.height.toFloat(), view.rootView.height.toFloat())
        motion.updateTravel(travelPx)
    }.lyricHeldComposite { held.active }) {
        val windows = if (held.active) held.layers.map { it.window } else outgoing.toList() + listOfNotNull(current)
        windows.forEach { window ->
            key(window) {
                val shown = window.presentation
                val old = window !== current
                val heldPreparing = !old && held.active && held.preparation != null
                val windowPreparing = preparing || heldPreparing
                val layer = rememberGraphicsLayer()
                val recorded = remember { booleanArrayOf(false) }
                val matchesPlayback = shown.id == track.id
                val settled by remember(window, motion) {
                    derivedStateOf { motion.settledAt(window.slot) }
                }
                val windowProgress = remember(window) { { window.playback.positionMs } }
                val windowSeeking = remember(window, old, matchesPlayback, preview) {
                    { preview.value != null && !old && matchesPlayback }
                }
                ImmersiveLyrics(
                    shown,
                    windowProgress,
                    { fraction ->
                        viewModel.seekPreview.requestLyricSeek(track.id, fraction)
                        viewModel.seekTo(fraction)
                    },
                    Modifier.fillMaxSize().lyricHeldWeight { if (held.active) held.weight(window) else null }.graphicsLayer {
                        translationY = if (held.active || (preparing && !old)) 0f else travelPx * (motion.offset.value + window.slot)
                    }.drawWithContent {
                        if (old) {
                            // 退场只记录一次，后续位移直接复用图层。
                            if (!recorded[0]) {
                                layer.record { this@drawWithContent.drawContent() }
                                recorded[0] = true
                            }
                            drawLayer(layer)
                        } else {
                            val recordHeldSource = heldTrackChange || held.active
                            recorded[0] = recordHeldSource
                            // 待入场窗口先完成绘制和定位，正式显示后直接绘制当前歌词。
                            if (recordHeldSource) {
                                layer.record { this@drawWithContent.drawContent() }
                                drawLayer(layer)
                            } else if (preparing) layer.record { this@drawWithContent.drawContent() }
                            else drawContent()
                        }
                    },
                    active = active && !old && matchesPlayback && !windowPreparing && settled,
                    followPlayback = followPlayback && !old && matchesPlayback && !windowPreparing && settled && !held.active,
                    onReady = if (!old && preparing) { { window.ready.complete(Unit); Unit } } else null,
                    exitAlignment = if (old) null else exitAlignment,
                    openingAlignment = if (old) null else openingAlignment,
                    prepareWhileHidden = !old && !held.active && (preparing || (prepareWhileHidden && matchesPlayback)),
                    // 切歌窗口仍按原锚点就绪；完整顶行仅用于单栏歌词开关入场。
                    revealTopLineBeforeEntrance = revealTopLineBeforeEntrance && !old && !preparing,
                    limitTransitionHistory = limitTransitionHistory && (!old || held.active) && !preparing,
                    heldPreparation = if (heldPreparing) held.preparation else null,
                    freezeWindow = old && held.active,
                    topExtensionPx = topExtensionPx,
                    topBufferPx = topBufferPx,
                    exitProgress = exitProgress,
                    entranceProgress = entranceProgress,
                    currentAnchorFraction = currentAnchorFraction,
                    seeking = windowSeeking,
                    progressLyricSeek = if (!old && matchesPlayback) window.playback.progressSeek(progressLyricSeek.value) else null,
                    onWindowLayout = if (!old && matchesPlayback && !held.active && onWindowLayout != null) {
                        { layout, protection -> onWindowLayout(layout,
                            if (preparing || !settled || outgoing.isNotEmpty()) LyricBlurProtection.HANDOFF else protection) }
                    } else null,
                    onOpeningFront = if (!old && matchesPlayback && !held.active && !preparing) onOpeningFront else null,
                )
            }
        }
        if (current == null) LyricAnimationEffect(exitAlignment) { exitAlignment?.complete(Unit) }
        if (current == null) LyricAnimationEffect(openingAlignment) { openingAlignment?.complete(Unit) }
    }
}

internal fun lyricTrackEnterOffset(fullHeight: Int, direction: TrackTransitionDirection): Int =
    if (direction == TrackTransitionDirection.NEXT) fullHeight else -fullHeight

internal fun lyricTrackExitOffset(fullHeight: Int, direction: TrackTransitionDirection): Int =
    -lyricTrackEnterOffset(fullHeight, direction)
