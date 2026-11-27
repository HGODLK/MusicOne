package com.musicone.sourceintegration

import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicone.*
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.*

/** 播放页只跟随主音源：主音源有自建播放页时所有歌曲（含其他音源的歌）都用它，没有时一律用原生播放器。 */
@Composable
internal fun rememberSourcePlayerUiBridge(viewModel: MusicPlaybackRuntime,
    form: SourcePlayerForm, mainSource: MusicSource): SourcePlayerUiBridge? {
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsStateWithLifecycle()
    val accountRevision by SourceApplicationHost.get(LocalContext.current).accountRevision.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.currentTrack == null) return null
    val instance = controller.running(mainSource.id) ?: return null
    return remember(instance, form, installed, accountRevision) {
        val provider = runCatching { instance.entry.playerUiProviderOrNull() }
        if (provider.isSuccess && provider.getOrNull() == null) null
        else {
            val declaration = provider.mapCatching { requireNotNull(it).playerUiDeclaration }
            if (declaration.getOrNull()?.supports(form) == false) null
            else SourcePlayerUiBridge(instance, viewModel, form, declaration.getOrNull(),
                declaration.exceptionOrNull()?.message)
        }
    }
}

/** 自建播放页的桥接对象，所有播放状态和操作最终还是由宿主统一执行。 */
internal class SourcePlayerUiBridge(
    private val instance: SourceInstance,
    private val playback: MusicPlaybackRuntime,
    val form: SourcePlayerForm,
    declaration: SourcePlayerUiDeclaration?,
    private val initialFailure: String?,
) {
    val identity: Any get() = this
    val opening = declaration?.opening ?: SourcePlayerOpening(0, false, emptySet())
    val motionReadiness = SourcePlayerLayoutReadiness()
    private var failure by mutableStateOf(initialFailure)
    fun reportMotionFailure(message: String) {
        failure = message.ifBlank { "音源播放页加载失败" }
        motionReadiness.markFailed()
    }

    @Composable
    fun Content(transition: SourcePlayerTransition, safeDrawingInsets: SourcePlayerInsets, dismiss: () -> Unit,
        sharedElement: @Composable (SourcePlayerSharedElement, Modifier, @Composable () -> Unit) -> Unit,
        onReady: () -> Unit, host: SourcePlayerHostMotion) {
        val state by playback.state.collectAsStateWithLifecycle()
        val progress by playback.playbackProgress.collectAsStateWithLifecycle()
        val activity by playback.playbackActivity.collectAsStateWithLifecycle()
        val favorites = LocalMusicFavorites.current
        val queue = LocalPlayerQueueState.current
        val track = state.currentTrack
        val latestState by rememberUpdatedState(state)
        val latestTransition by rememberUpdatedState(transition)
        val latestInsets by rememberUpdatedState(safeDrawingInsets)
        val latestDismiss by rememberUpdatedState(dismiss)
        val latestSharedElement by rememberUpdatedState(sharedElement)
        val latestFavorite by rememberUpdatedState(favorites)
        val menu = LocalMainSourcePlaylistSongMenu.current
        val latestMenu by rememberUpdatedState(menu)
        val latestDensity by rememberUpdatedState(LocalDensity.current.density)
        val scope = rememberCoroutineScope()
        val seekGesture = remember(this) { SourcePlayerSeekGesture(playback, scope) }
        val queueDrag = remember(this, queue) { SourcePlayerQueueDrag(queue) }
        val dismissDrag = remember(this) { SourcePlayerDismissDrag() }
        val lyrics = remember(this) { SourcePlayerLyricsController(scope) }
        val latestHost by rememberUpdatedState(host)
        val latestPresentation by rememberUpdatedState(LocalPhonePlayerPresentation.current)
        val rootOffset = LocalMotionRootOffset.current
        var pageCoordinates by remember(this) { mutableStateOf<LayoutCoordinates?>(null) }
        // 切歌或卸载时结束未完成的预览与拖动，不把半截手势留给下一首或下一个页面。
        DisposableEffect(this, track?.id) { onDispose { seekGesture.dispose(); queueDrag.cancel() } }
        DisposableEffect(this) { onDispose { dismissDrag.cancel(latestDismiss) } }
        HighRefreshRateWhile(seekGesture.previewing)
        LaunchedEffect(transition.interactive) { if (!transition.interactive) { seekGesture.endPreview(); queueDrag.cancel() } }
        lyrics.Drive(track, state.lyricLoadState)
        val artworkColors = sourcePlayerArtworkColors(track)
        val snapshotResult = remember(state, favorites.state.ids, artworkColors) {
            runCatching { sourcePlayerSnapshot(state, track?.id in favorites.state.ids, artworkColors) }
        }
        // 在包读到新快照之前登记当前歌曲，包用公共组件显示其他音源的歌曲时才不会被拦下。
        snapshotResult.getOrNull()?.track?.track?.identity?.let {
            SourcePlayerDisplayedTracks.record(this, instance.entry.descriptor.id, it)
        }
        DisposableEffect(this) { onDispose { SourcePlayerDisplayedTracks.release(this@SourcePlayerUiBridge) } }
        SideEffect { snapshotResult.exceptionOrNull()?.let { reportMotionFailure(it.message ?: "播放页状态读取失败") } }
        val snapshotState by rememberUpdatedState(snapshotResult.getOrElse {
            SourcePlayerSnapshot(null, false, false, "ALL", emptyList(), null, false, false, it.message)
        })
        val timelineState by rememberUpdatedState(sourcePlayerTimeline(state, progress, activity))
        // 队列、歌词、音频规格按需转换，包经 context 读取，变化时自动触发重读。
        val queueSnapshot = remember(state.queue, state.currentTrack?.id) { sourcePlayerQueueSnapshot(state) }
        SourcePlayerDisplayedTracks.recordQueue(this, instance.entry.descriptor.id,
            queueSnapshot.upcoming.mapTo(HashSet()) { it.track.identity })
        val queueState by rememberUpdatedState(queueSnapshot)
        val lyricsState by rememberUpdatedState(remember(track, state.lyricLoadState) {
            sourcePlayerLyrics(track, state.lyricLoadState)
        })
        val audioInfoState = rememberSourcePlayerAudioInfo()
        val handoff = LocalPlayerControlHandoff.current
        DisposableEffect(this) { onDispose { failure = initialFailure } }
        val sourceId = instance.entry.descriptor.id
        val context = remember(this) {
            /**
             * 返回 false 表示本页实例已被替换（音源包重载、卸载或换了主音源），调用方静默忽略。
             * 包页面会在 graphicsLayer／绘制阶段读取宿主状态，那里抛异常会直接闪退整个应用，
             * 所以实例失效只让读取返回最后一份状态、操作不执行，等下一次组合换上新页面。
             * 主音源的播放页也承载其他音源的歌曲，这里不要求当前歌曲属于本音源。
             */
            fun checkCurrent(interactive: Boolean = false): Boolean {
                check(Looper.myLooper() == Looper.getMainLooper()) { "播放器页面操作必须在主线程执行" }
                if (SourcePackageController.current()?.running(sourceId) !== instance) return false
                val current = playback.state.value
                if (interactive) check(latestTransition.interactive && current.playerExpanded && failure == null) { "播放页当前不可操作" }
                return true
            }
            // 菜单、队列、详情页打开时不接手下拉收起与歌词开合手势。
            fun gestureBlocked() = latestMenu?.expanded == true || latestMenu?.visible == true ||
                queue.visible || latestHost.relatedOpen || latestPresentation?.menuLocks?.active == true
            val lyricsMotion = lyrics.sdkMotion(::checkCurrent, ::gestureBlocked, { latestDensity }) { style, modifier ->
                val current = latestState.currentTrack
                if (current != null) lyrics.Panel(style, modifier, current, latestState.lyricLoadState, playback,
                    latestTransition.interactive, latestState.trackTransitionDirection)
            }
            object : SourcePlayerUiContext, SourcePlayerSharedSourceAccess, SourcePlayerLyricsMotionAccess,
                SourcePlayerQueueAccess, SourcePlayerLyricsDataAccess, SourcePlayerAudioInfoAccess, SourcePlayerBackdropAccess {
                override val queueSnapshot get(): SourcePlayerQueueSnapshot { checkCurrent(); return queueState }
                override val lyricsData get(): SourcePlayerLyrics { checkCurrent(); return lyricsState }
                override val audioInfo get(): SourcePlayerAudioInfo? { checkCurrent(); return audioInfoState.value }
                @Composable override fun Backdrop(modifier: Modifier) {
                    checkCurrent()
                    val current = latestState.currentTrack ?: return
                    SourcePlayerBackdropContent(current, latestTransition.interactive || latestTransition.opening,
                        { latestState.trackTransitionDirection }, modifier)
                }
                override val lyricsMotion: SourcePlayerLyricsMotion get() { checkCurrent(); return lyricsMotion }
                override fun sharedSource(element: SourcePlayerSharedElement): SourcePlayerSharedSource? {
                    if (!checkCurrent()) return null
                    if (element !in opening.sharedElements) return null
                    return sourcePlayerSharedSource(latestHost.motion, element, pageCoordinates, rootOffset, latestDensity, handoff)
                }
                override val snapshot get(): SourcePlayerSnapshot { checkCurrent(); return snapshotState }
                override val timeline get(): SourcePlayerTimeline { checkCurrent(); return timelineState }
                override val form get() = this@SourcePlayerUiBridge.form
                override val safeDrawingInsets get(): SourcePlayerInsets { checkCurrent(); return latestInsets }
                override val transition get() = latestTransition
                override fun dismiss() { if (checkCurrent()) latestDismiss() }
                override fun onFailure(message: String) { if (checkCurrent()) reportMotionFailure(message) }
                override val actions: SourcePlayerUiActions get() {
                    checkCurrent()
                    val expectedTrack = playback.state.value.currentTrack?.id
                    val expectedGeneration = playback.playbackProgress.value.generation
                    fun action(block: () -> Unit) {
                        if (!checkCurrent(true)) return
                        check(playback.state.value.currentTrack?.id == expectedTrack &&
                            playback.playbackProgress.value.generation == expectedGeneration) { "播放页操作所属歌曲已切换" }
                        block()
                    }
                    fun menuBlocked() = latestMenu?.expanded == true || latestMenu?.visible == true
                    fun checkSeek(trackId: String, generation: Long, positionMs: Long): Long {
                        val current = playback.state.value
                        require(sourcePlayerSeekMatches(trackId, generation, current.currentTrack?.id, playback.playbackProgress.value)) { "播放进度已过期" }
                        val duration = current.currentTrack?.durationMs ?: 0L
                        require(duration > 0L && positionMs in 0L..duration) { "播放位置不在歌曲范围内" }
                        return duration
                    }
                    return object : SourcePlayerUiActions, SourcePlayerSeekPreviewActions,
                        SourcePlayerQueueGestureActions, SourcePlayerTrackMenuActions, SourcePlayerDismissGestureActions {
                    override fun dismissDragStart(): Boolean {
                        if (!checkCurrent(true)) return false
                        return !gestureBlocked() && !queueDrag.active && !lyrics.motion.dragging &&
                            dismissDrag.start(latestHost)
                    }
                    override fun dismissDrag(deltaPx: Float) { if (checkCurrent()) dismissDrag.drag(deltaPx) }
                    override fun dismissDragEnd(velocityPxPerSecond: Float) {
                        // 实例失效时不提交收起，只撤回拖动。
                        if (!checkCurrent()) { dismissDrag.cancel(latestDismiss); return }
                        dismissDrag.end(velocityPxPerSecond, latestDensity, latestDismiss)
                    }
                    override fun dismissDragCancel() { checkCurrent(); dismissDrag.cancel(latestDismiss) }
                    override fun togglePlay() = action { playback.togglePlay() }
                    override fun next() = action { playback.requestNext() }
                    override fun previous() = action { playback.requestPrevious() }
                    override fun seekTo(trackId: String, generation: Long, positionMs: Long) = action {
                        val duration = checkSeek(trackId, generation, positionMs)
                        seekGesture.commit(trackId, generation, timelineState.positionMs, positionMs, duration)
                    }
                    override fun previewSeek(trackId: String, generation: Long, positionMs: Long?) {
                        // 结束预览属于收尾，页面已不可操作或已切歌时也允许执行。
                        if (positionMs == null) { checkCurrent(); seekGesture.endPreview(); return }
                        action { seekGesture.preview(trackId, positionMs, checkSeek(trackId, generation, positionMs)) }
                    }
                    override fun queueDragStart(): Boolean {
                        if (!checkCurrent(true)) return false
                        return !menuBlocked() && !dismissDrag.active && queueDrag.start()
                    }
                    override fun queueDrag(deltaPx: Float) { if (checkCurrent()) queueDrag.drag(deltaPx) }
                    override fun queueDragEnd(velocityPxPerSecond: Float) {
                        if (!checkCurrent()) { queueDrag.cancel(); return }
                        queueDrag.end(velocityPxPerSecond, latestDensity, blocked = menuBlocked() || !latestTransition.interactive)
                    }
                    override fun queueDragCancel() { checkCurrent(); queueDrag.cancel() }
                    override fun openTrackMenu(left: Float, top: Float, right: Float, bottom: Float,
                        lyricOffset: Boolean): Boolean {
                        if (!checkCurrent(true)) return false
                        val current = playback.state.value.currentTrack ?: return false
                        val target = latestMenu ?: return false
                        if (!playerTrackMenuEnabled(current, lyricOffset)) return false
                        val anchor = sourcePlayerAnchorInRoot(pageCoordinates, left, top, right, bottom) ?: return false
                        target.open(current, anchor, fromLyricsTitle = lyricOffset)
                        return true
                    }
                    override fun cyclePlaybackMode() = action { playback.cyclePlaybackMode() }
                    override fun selectQuality(id: String) = action {
                        val quality = sourcePlayerQualityOptions(playback.state.value).firstOrNull { it.qualityId == id }
                        requireNotNull(quality) { "音质不在当前可选目录中" }
                        playback.selectQuality(quality)
                    }
                    override fun refreshQualities() = action { playback.refreshQualityOptions() }
                    override fun openQueue() = action { queue.open() }
                    override fun toggleFavorite() = action { playback.state.value.currentTrack?.let(latestFavorite.toggle) }
                    override fun retryLyrics() = action { playback.retryLyrics() }
                    }
                }
                @Composable override fun Lyrics(modifier: Modifier) {
                    checkCurrent()
                    val current = latestState.currentTrack ?: return
                    // 原生歌词组件保留时间、寻焦与滚动实现，包仅决定区域和主题。
                    CompositionLocalProvider(LocalPlayerVisible provides latestTransition.interactive) {
                        PlayerSyncedLyricsContent(current, latestState.lyricLoadState, playback, modifier,
                            active = latestTransition.interactive, followPlayback = latestTransition.interactive,
                            trackTransitionDirection = latestState.trackTransitionDirection)
                    }
                }
                @Composable override fun SharedElement(element: SourcePlayerSharedElement, modifier: Modifier,
                    content: @Composable () -> Unit) {
                    checkCurrent()
                    require(element in opening.sharedElements) { "该联动元素未在播放页声明中启用" }
                    latestSharedElement(element, modifier, content)
                }
            }
        }
        if (failure == null) key(context) {
            // 记录包页面根坐标，关联菜单锚点由此从页面坐标换算到根坐标。
            Box(Modifier.fillMaxSize().onGloballyPositioned { pageCoordinates = it }) {
                SourceExternalSurface(instance, SourceRoute("player"), mode = "player", onExit = dismiss,
                    // 跟手下拉收起时页面阶段已离开 SHOWN，仍须把后续手指事件交给包。
                    interactive = transition.interactive || dismissDrag.active, playerUi = context,
                    onFailure = ::reportMotionFailure, onReady = onReady)
            }
        } else Surface(Modifier.fillMaxSize()) {
            SideEffect { motionReadiness.markFailed(); onReady() }
            Column(Modifier.absolutePadding(safeDrawingInsets.left, safeDrawingInsets.top,
                safeDrawingInsets.right, safeDrawingInsets.bottom).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("音源播放页暂不可用", style = MaterialTheme.typography.titleLarge)
                Text(failure.orEmpty())
                Button(onClick = dismiss) { Text("返回") }
            }
        }
    }
}
