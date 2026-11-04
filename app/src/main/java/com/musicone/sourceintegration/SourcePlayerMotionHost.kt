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
import com.musicone.*
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.source.sdk.SourcePlayerTransition
import com.musicone.source.sdk.SourcePlayerForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** 方案选择和临时动效策略独立于根页面，原生播放器继续使用原来的准备路径。 */
@Composable
internal fun rememberSourcePlayerMotion(playback: MusicPlaybackRuntime, motion: PageMotion,
    presentation: PhonePlayerPresentation): SourcePlayerUiBridge? {
    val form = when {
        !isPhonePlayerConfiguration(androidx.compose.ui.platform.LocalConfiguration.current) -> SourcePlayerForm.TABLET
        presentation.latestRotation != 0 -> SourcePlayerForm.PHONE_LANDSCAPE
        else -> SourcePlayerForm.PHONE_PORTRAIT
    }
    val bridge = rememberSourcePlayerUiBridge(playback, form)
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

/** 首帧由独立包组合明确回执，共享目标只等待现有的一轮布局预算。 */
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

/** 自建播放器只共享宿主开合进度；关闭始终是整页淡出，不接管原生转向准备。 */
@Composable
internal fun SourcePlayerMotionHost(motion: PageMotion, bridge: SourcePlayerUiBridge, dismiss: () -> Unit) {
    val presentation = LocalPhonePlayerPresentation.current
    val rotation = if (presentation?.isPhone == true) presentation.latestRotation else 0
    val safeDrawingInsets = sourcePlayerSafeDrawingInsets(rotation)
    key(bridge.identity) {
        var lastOpeningProgress by remember { mutableFloatStateOf(motion.value) }
        val pageProgress = if (motion.wantsOpen) motion.value else lastOpeningProgress
        SideEffect { if (motion.wantsOpen) lastOpeningProgress = motion.value }
        DisposableEffect(motion) {
            // 外部页不读取原生目标；新实例也不能继承上一实例或上一形态的目标。
            motion.targets.clear()
            onDispose { motion.targets.clear() }
        }
        Box(Modifier.fillMaxSize().onGloballyPositioned { motion.updateHost(it.boundsInRoot()) }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .graphicsLayer { alpha = motion.value.coerceIn(0f, 1f) }) {
            PhonePlayerWindowHost(rotation) {
                bridge.Content(
                    SourcePlayerTransition(pageProgress, motion.wantsOpen,
                        motion.phase == MotionPhase.SHOWN, ExperiencePreferences.options.reduceMotion),
                    safeDrawingInsets, dismiss,
                    sharedElement = { element, modifier, content ->
                    SourcePlayerSharedContent(motion, bridge.motionReadiness, element, rotation,
                        bridge.opening.barParticipates && element in bridge.opening.sharedElements,
                        modifier, content)
                    }, onReady = bridge.motionReadiness::markReady,
                )
            }
        }
    }
}

/** 变换包自己绘制的内容，不用宿主字体、图标或封面替代目标页面。 */
@Composable
private fun SourcePlayerSharedContent(motion: PageMotion, readiness: SourcePlayerLayoutReadiness, element: SourcePlayerSharedElement,
    rotation: Int, enabled: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val key = element.sourceAnchorKey()
    val rootOffset = LocalMotionRootOffset.current
    val rootView = LocalView.current
    var targetOrigin by remember { mutableStateOf<Offset?>(null) }
    var targetSize by remember { mutableStateOf(IntSize.Zero) }
    // 松手收起时保留当前共享元素姿态，随后只执行宿主整页淡出。
    val openingPose = remember(motion) { derivedStateOf {
        if (motion.wantsOpen) motion.value.coerceIn(0f, 1f) else null
    } }
    val retainedOpeningProgress = remember { floatArrayOf(1f) }
    val owner = remember { Any() }
    DisposableEffect(readiness, element) {
        onDispose { readiness.remove(element, owner) }
    }
    Box(modifier.onGloballyPositioned {
        val point = it.localToRoot(Offset.Zero)
        // 包页面有独立 ComposeView；它的根坐标不包含外层手机画布的物理旋转。
        targetOrigin = sourcePlayerSharedTargetOrigin(point, rootOffset,
            IntSize(rootView.width, rootView.height), rotation)
        targetSize = it.size
        if (sourcePlayerSharedTargetValid(it.size)) readiness.publish(element, owner)
        else readiness.remove(element, owner)
    }, propagateMinConstraints = true) {
    Box(Modifier.graphicsLayer {
        val progress = openingPose.value?.also { retainedOpeningProgress[0] = it } ?: retainedOpeningProgress[0]
        val source = motion.sourceSnapshot[key] ?: motion.sources[key]
        val origin = targetOrigin
        if (enabled && source != null && origin != null && targetSize.width > 0 && targetSize.height > 0) {
            val delta = source.bounds.topLeft - origin
            // 绘制层位移属于横向内容坐标，源端仍在竖屏物理窗口中。
            val localDelta = when (rotation) {
                90 -> Offset(delta.y, -delta.x)
                -90 -> Offset(-delta.y, delta.x)
                else -> delta
            }
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = localDelta.x * (1f - progress)
            translationY = localDelta.y * (1f - progress)
            rotationZ = -rotation * (1f - progress)
            scaleX = 1f + (source.bounds.width / targetSize.width - 1f) * (1f - progress)
            scaleY = 1f + (source.bounds.height / targetSize.height - 1f) * (1f - progress)
        }
    }) { content() }
    }
}

internal fun sourcePlayerSharedTargetValid(size: IntSize): Boolean = size.width > 0 && size.height > 0

internal fun sourcePlayerSharedTargetOrigin(point: Offset, rootOffset: Offset, rootSize: IntSize,
    rotation: Int): Offset = rootOffset + when (rotation) {
    90 -> Offset(rootSize.height.toFloat() - point.y, point.x)
    -90 -> Offset(point.y, rootSize.width.toFloat() - point.x)
    else -> point
}

private fun SourcePlayerSharedElement.sourceAnchorKey(): String = when (this) {
    SourcePlayerSharedElement.ARTWORK -> "cover"
    SourcePlayerSharedElement.TITLE -> "title"
    SourcePlayerSharedElement.ARTIST -> "subtitle"
    SourcePlayerSharedElement.PLAY_PAUSE -> "play"
}
