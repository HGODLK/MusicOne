package com.musicone.sourceintegration

import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicone.*
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.*

/** 播放页属于当前歌曲的音源，与正在浏览的首页音源无关。 */
@Composable
internal fun rememberSourcePlayerUiBridge(viewModel: MusicPlaybackRuntime,
    form: SourcePlayerForm): SourcePlayerUiBridge? {
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsStateWithLifecycle()
    val accountRevision by SourceApplicationHost.get(LocalContext.current).accountRevision.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val instance = controller.running(state.currentTrack?.source?.id ?: return null) ?: return null
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

/** 只持有挂载桥，核心状态和所有动作始终由宿主拥有。 */
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
        onReady: () -> Unit) {
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
        val snapshotResult = remember(state, favorites.state.ids) {
            runCatching { sourcePlayerSnapshot(state, track?.id in favorites.state.ids) }
        }
        SideEffect { snapshotResult.exceptionOrNull()?.let { reportMotionFailure(it.message ?: "播放页状态读取失败") } }
        val snapshotState by rememberUpdatedState(snapshotResult.getOrElse {
            SourcePlayerSnapshot(null, false, false, "ALL", emptyList(), null, false, false, it.message)
        })
        val timelineState by rememberUpdatedState(sourcePlayerTimeline(state, progress, activity))
        DisposableEffect(this) { onDispose { failure = initialFailure } }
        val sourceId = instance.entry.descriptor.id
        val context = remember(this) {
            fun checkCurrent(interactive: Boolean = false) {
                check(Looper.myLooper() == Looper.getMainLooper()) { "播放器页面操作必须在主线程执行" }
                check(SourcePackageController.current()?.running(sourceId) === instance) { "播放页音源已失效" }
                val current = playback.state.value
                check(current.currentTrack?.source?.id == sourceId) { "播放页音源已切换" }
                if (interactive) check(latestTransition.interactive && current.playerExpanded && failure == null) { "播放页当前不可操作" }
            }
            object : SourcePlayerUiContext {
                override val snapshot get(): SourcePlayerSnapshot { checkCurrent(); return snapshotState }
                override val timeline get(): SourcePlayerTimeline { checkCurrent(); return timelineState }
                override val form get() = this@SourcePlayerUiBridge.form
                override val safeDrawingInsets get(): SourcePlayerInsets { checkCurrent(); return latestInsets }
                override val transition get() = latestTransition
                override fun dismiss() { checkCurrent(); latestDismiss() }
                override fun onFailure(message: String) { checkCurrent(); reportMotionFailure(message) }
                override val actions: SourcePlayerUiActions get() {
                    checkCurrent()
                    val expectedTrack = playback.state.value.currentTrack?.id
                    val expectedGeneration = playback.playbackProgress.value.generation
                    fun action(block: () -> Unit) {
                        checkCurrent(true)
                        check(playback.state.value.currentTrack?.id == expectedTrack &&
                            playback.playbackProgress.value.generation == expectedGeneration) { "播放页操作所属歌曲已切换" }
                        block()
                    }
                    return object : SourcePlayerUiActions {
                    override fun togglePlay() = action { playback.togglePlay() }
                    override fun next() = action { playback.requestNext() }
                    override fun previous() = action { playback.requestPrevious() }
                    override fun seekTo(trackId: String, generation: Long, positionMs: Long) = action {
                        val current = playback.state.value
                        require(sourcePlayerSeekMatches(trackId, generation, current.currentTrack?.id, playback.playbackProgress.value)) { "播放进度已过期" }
                        val duration = current.currentTrack?.durationMs ?: 0L
                        require(duration > 0L && positionMs in 0L..duration) { "播放位置不在歌曲范围内" }
                        playback.seekToForTrack(trackId, positionMs.toFloat() / duration)
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
            SourceExternalSurface(instance, SourceRoute("player"), mode = "player", onExit = dismiss,
                interactive = transition.interactive, playerUi = context,
                onFailure = ::reportMotionFailure, onReady = onReady)
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
