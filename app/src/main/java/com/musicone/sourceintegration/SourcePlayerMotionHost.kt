package com.musicone.sourceintegration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicone.*
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.source.sdk.SourcePlayerTransition
import com.musicone.source.sdk.SourcePlayerForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** 根据当前主音源决定走自建播放页还是原生播放页的开合准备流程，不影响原生播放器。 */
@Composable
internal fun rememberSourcePlayerMotion(playback: MusicPlaybackRuntime, motion: PageMotion,
    presentation: PhonePlayerPresentation, mainSource: MusicSource): SourcePlayerUiBridge? {
    val form = when {
        !isPhonePlayerConfiguration(androidx.compose.ui.platform.LocalConfiguration.current) -> SourcePlayerForm.TABLET
        presentation.latestRotation != 0 -> SourcePlayerForm.PHONE_LANDSCAPE
        else -> SourcePlayerForm.PHONE_PORTRAIT
    }
    val bridge = rememberSourcePlayerUiBridge(playback, form, mainSource)
    val currentBridge = rememberUpdatedState(bridge)
    SideEffect {
        presentation.customPlayer = bridge != null
        motion.waitForTarget = true
        motion.openingDurationOverride = bridge?.opening?.durationMillis
        motion.openingPreparationStarted = bridge?.let { it.motionReadiness::reset }
        motion.openingPreparationOverride = if (bridge != null) suspend { awaitCurrentPlayerReady(currentBridge, motion) } else null
        motion.prepareSharedAnchors = if (bridge == null && presentation.isPhone)
            presentation.openingAnchors::ready else null
    }
    return bridge
}

/** 准备期间切歌或更换实例时取消旧页等待，始终交接给当前真正应显示的页面。 */
private suspend fun awaitCurrentPlayerReady(current: State<SourcePlayerUiBridge?>, motion: PageMotion) {
    while (true) {
        val player = current.value ?: return
        val completed = coroutineScope {
            val ready = async {
                player.motionReadiness.awaitReady(player.opening.sharedElements, player::reportMotionFailure) {
                    val bounds = motion.sources[it.sourceAnchorKey()]?.bounds
                    bounds != null && bounds.width > 0f && bounds.height > 0f
                }
                true
            }
            val changed = async { snapshotFlow { current.value }.first { it !== player }; false }
            try {
                select<Boolean> { ready.onAwait { it }; changed.onAwait { it } }
            } finally { ready.cancel(); changed.cancel() }
        }
        if (completed) return
    }
}

/** 等待独立 ComposeView 里的包页面布局就绪，再放行播放页展开动画。 */
internal class SourcePlayerLayoutReadiness {
    private var contentReady by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private val targets = mutableStateMapOf<SourcePlayerSharedElement, Any>()

    fun reset() { contentReady = false; failed = false; targets.clear() }
    fun markReady() { contentReady = true }
    fun markFailed() { failed = true }
    fun publish(element: SourcePlayerSharedElement, owner: Any) { targets[element] = owner }
    fun remove(element: SourcePlayerSharedElement, owner: Any) {
        if (targets[element] === owner) targets.remove(element)
    }

    suspend fun awaitReady(expected: Set<SourcePlayerSharedElement>, onFailure: (String) -> Unit,
        sourceReady: (SourcePlayerSharedElement) -> Boolean = { true }) {
        // 首次包载入并不是布局超时；错误页面也通过相同回执释放开页准备。
        snapshotFlow { contentReady }.first { it }
        if (failed) return
        val ready = withTimeoutOrNull(160L) {
            while (!failed && (!targets.keys.containsAll(expected) || !expected.all(sourceReady))) withFrameNanos { }
            true
        } == true
        if (!ready && !failed) onFailure("音源播放页缺少已声明的共享元素：" +
            expected.filter { it !in targets || !sourceReady(it) }.joinToString { it.name })
    }
}

/** 只有从迷你条展开的页面才能接原生玻璃，且只在未旋转的页面上。 */
internal fun com.musicone.source.sdk.SourcePlayerOpening.usesGlass(rotation: Int): Boolean =
    glass && !pageFade && rotation == 0

/** 副本画当前歌曲；播放键跟随交接中冻结的播放状态，与迷你条起点一致。 */
@Composable
private fun sourcePlayerBarCopyFor(motion: PageMotion, element: SourcePlayerSharedElement, state: MusicOneUiState) {
    val track = state.currentTrack ?: return
    val anchor = motion.sourceSnapshot[element.sourceAnchorKey()] ?: motion.sources[element.sourceAnchorKey()]
    val playing = LocalPlayerControlHandoff.current?.displayedPlaying(state.isPlaying) ?: state.isPlaying
    SourcePlayerBarCopy(element, track, playing, anchor?.textSizeSp ?: 0f)
}

/** 自建播放页的动画宿主：按音源声明走整页淡入或从迷你条展开，共享宿主开合进度。 */
@Composable
internal fun SourcePlayerMotionHost(motion: PageMotion, bridge: SourcePlayerUiBridge, viewModel: MusicPlaybackRuntime,
    dismiss: () -> Unit, transitionBackdrop: PlayerTransitionBackdrop? = null) {
    val presentation = LocalPhonePlayerPresentation.current
    val rotation = if (presentation?.isPhone == true) presentation.latestRotation else 0
    val safeDrawingInsets = sourcePlayerSafeDrawingInsets(rotation)
    key(bridge.identity) {
        val opening = bridge.opening
        // 原生玻璃按竖屏几何展开；手机横屏页面已旋转，不画玻璃。
        val glass = opening.usesGlass(rotation)
        val favorites = LocalMusicFavorites.current
        var lastOpeningProgress by remember { mutableFloatStateOf(motion.value) }
        // 整页淡出时进度停在开页终点；从迷你条展开时收起也按真实进度回到 0，由包安排退场。
        val pageProgress = if (motion.wantsOpen || !opening.pageFade) motion.value else lastOpeningProgress
        SideEffect { if (motion.wantsOpen) lastOpeningProgress = motion.value }
        DisposableEffect(motion) {
            // 外部页不读取原生目标；新实例也不能继承上一实例或上一形态的目标。
            motion.targets.clear()
            onDispose { motion.targets.clear() }
        }
        Box(Modifier.fillMaxSize().onGloballyPositioned { motion.updateHost(it.boundsInRoot()) }
            // 玻璃需要整页表面作为展开终点；不画玻璃时保持“外部页不登记原生目标”。
            .then(if (glass) Modifier.motionAnchor(motion, "surface", true, hideDuringMotion = false) else Modifier)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .graphicsLayer {
                alpha = if (opening.pageFade) motion.value.coerceIn(0f, 1f)
                    // 准备期包页面尚未布局，不能先在迷你条位置露出空白背景。
                    else if (motion.phase == MotionPhase.PREPARING) 0f else 1f
            }) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            // 与原生播放页共用关联菜单宿主：菜单按锚点弹出，歌手／专辑详情在播放页内部入栈，返回回到菜单。
            CompositionLocalProvider(LocalPhonePlayerRotation provides rotation) {
            PlayerRelatedMenuHost(state, viewModel) { relatedOpen ->
            PhonePlayerWindowHost(rotation) {
                val track = state.currentTrack
                // 玻璃在包页面下方，包页面展开前段背景透明时透出；共享元素仍在包页面里，不被玻璃盖住。
                if (glass && track != null) SourcePlayerGlassLayer(motion, transitionBackdrop, track)
                Box(Modifier.fillMaxSize().sourcePlayerSurfaceReveal(motion, enabled = !opening.pageFade)) {
                bridge.Content(
                    SourcePlayerTransition(pageProgress, motion.wantsOpen,
                        motion.phase == MotionPhase.SHOWN, ExperiencePreferences.options.reduceMotion),
                    safeDrawingInsets, dismiss,
                    sharedElement = { element, modifier, content ->
                    val shared = opening.barParticipates && element in opening.sharedElements
                    val barCopy: (@Composable () -> Unit)? = if (shared && opening.barCrossfade &&
                        element != SourcePlayerSharedElement.ARTWORK) {
                        { sourcePlayerBarCopyFor(motion, element, state) }
                    } else null
                    SourcePlayerSharedContent(motion, bridge.motionReadiness, element, rotation,
                        shared, opening, modifier, barCopy, content)
                    }, onReady = bridge.motionReadiness::markReady,
                    host = SourcePlayerHostMotion(motion, rotation, relatedOpen),
                )
                }
                // 迷你条整体让位给玻璃后，收藏与下一首沿原生方式在原位淡出。
                if (glass && track != null) FlyingMiniPlayerAccessories(motion, track.id in favorites.state.ids)
            }
            }
            }
        }
    }
}
