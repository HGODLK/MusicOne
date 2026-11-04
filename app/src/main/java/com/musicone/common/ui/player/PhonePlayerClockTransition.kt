package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** 时钟保持单一实例，退出阶段继续沿用最后横屏的测量尺寸。 */
@Composable
internal fun PhonePlayerClockTransition(visualTrack: MusicTrack, motion: PageMotion,
    showLyrics: Boolean, contentActivated: Boolean, rotation: Int) {
    val turn = LocalPhonePlayerTurn.current
    val presentation = LocalPhonePlayerPresentation.current
    var lastRotation by remember { mutableIntStateOf(rotation.takeIf { it != 0 } ?: 90) }
    val clockRotation = if (rotation != 0) rotation else lastRotation
    SideEffect { if (rotation != 0) lastRotation = rotation }
    val clockFlight = remember { PhoneClockFlight() }
    val handoff = remember { PhoneClockHandoff() }
    val entrance = clockFlight.progress
    val exposure = remember { Animatable(1f) }
    var nativeEntry by remember { mutableStateOf(false) }
    val artwork by rememberPlayerArtwork(visualTrack)
    val playerVisible = LocalPlayerVisible.current
    var travel by remember { mutableFloatStateOf(1f) }
    val hasEntered by remember { derivedStateOf { entrance.value > 0f } }
    DisposableEffect(presentation) { onDispose { presentation?.additionalMotion("clock", false) } }

    val target = if (rotation != 0 && motion.wantsOpen && !showLyrics) 1f else 0f
    val preparing = presentation?.preparation?.preparing == true
    val fading = presentation?.preparation?.fading == true
    val generation = turn?.generation ?: 0
    val revealWithPage = rotation != 0 && motion.mounted && motion.wantsOpen &&
        motion.phase != MotionPhase.SHOWN && !motion.targetContentHandoff
    // 手势阶段切换不会重启相同入口；只有目标方向、可见状态或真正首次展开改变才续接。
    val request = phoneClockTransitionRequest(target, rotation, revealWithPage, preparing, fading, generation)
    PhoneClockHandoffRecovery(handoff, request, visualTrack, turn, clockFlight,
        exposure = { exposure.value }, nativeDrawing = { nativeEntry })
    LaunchedEffect(request, handoff.revision) {
        val owner = handoff.begin()
        try {
            if (target == 0f) {
                clockFlight.clearEndpointWait()
                presentation?.preparation?.clockEntered(generation)
                presentation?.additionalMotion("clock", entrance.value != 0f)
                // 隐藏请求持续有效，但旧快照撤下前不消耗退场行程；快速改向也沿用当前速度。
                clockFlight.exitWhenVisible(
                    covered = { presentation?.preparation?.let { it.active || it.image != null } == true },
                    travel = { travel },
                    beforeExit = { exposure.snapTo(1f) },
                )
                return@LaunchedEffect
            }
            if (preparing) return@LaunchedEffect
            if (fading) {
                nativeEntry = true
                clockFlight.clearEndpointWait()
                exposure.snapTo(1f)
                clockFlight.snapTo(target)
                return@LaunchedEffect
            }
            PlayerMotionDiagnostics.event("clock-request") {
                "rotation=$rotation clockRotation=$clockRotation lyrics=$showLyrics target=$target " +
                    "entrance=${entrance.value} reveal=$revealWithPage phase=${motion.phase} travel=$travel"
            }
            presentation?.additionalMotion("clock", entrance.value != target)
            try {
                // 入口跟随本轮代次续接；目标准备不限时，入口预算从旧帧释放后开始。
                // 进度已经为一也可能仍绑定旧方向；先复核实际端点，不能只凭动画数值宣布完成。
                if (target == 1f) {
                    val sharedBudget = presentation?.preparation?.clockWaitMillis(generation)
                        ?: PHONE_PLAYER_TURN_PREPARATION_MILLIS
                    val budget = clockFlight.endpointBudget(generation, sharedBudget)
                    if (!awaitPhoneClockEndpoint(turn, rotation, budget)) {
                        nativeEntry = true
                        exposure.snapTo(0f)
                        clockFlight.snapTo(1f)
                        if (ExperiencePreferences.options.reduceMotion) exposure.snapTo(1f)
                        else exposure.animateTo(1f, musicMotion(240))
                        clockFlight.clearEndpointWait()
                        presentation?.preparation?.clockEntered(generation)
                        return@LaunchedEffect
                    }
                    nativeEntry = false
                }
                if (entrance.value == target && clockFlight.velocity == 0f && exposure.value == 1f) {
                    clockFlight.clearEndpointWait()
                    if (target == 1f) presentation?.preparation?.clockEntered(generation)
                    return@LaunchedEffect
                }
                val velocity = clockFlight.velocity
                if (phoneClockRevealWithPage(revealWithPage, entrance.value, velocity)) {
                    // 直接从迷你条打开时，时钟随原整页显露，不在开合结束后再启动第二段进场。
                    clockFlight.snapTo(target)
                } else {
                    if (entrance.value == 0f) exposure.snapTo(1f)
                    clockFlight.animateTo(target, travel)
                }
                if (target == 1f) {
                    if (exposure.value != 1f) exposure.animateTo(1f, musicMotion(240))
                    presentation?.preparation?.clockEntered(generation)
                    clockFlight.clearEndpointWait()
                } else exposure.snapTo(1f)
            } finally {
                PlayerMotionDiagnostics.event("clock-finish") { "target=$target actual=${entrance.value} rotation=$rotation" }
            }
        } finally {
            if (handoff.finish(owner)) presentation?.additionalMotion("clock", false)
        }
    }

    // 竖屏仅将时钟停在屏外，保留字体测量和逐位滚动节点，转回横屏不再重新初始化。
    Box(Modifier.fillMaxSize().playerDetailReveal(motion).graphicsLayer {
        alpha = if (hasEntered) exposure.value else 0f
    }) {
        PhonePlayerWindowHost(clockRotation) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(phonePlayerLandscapePadding(clockRotation, balanceHorizontal = true))) {
                val canvasWidth = maxWidth.value
                Layout(modifier = Modifier.fillMaxSize(), content = {
                    PhoneTurnElement("clock", physicalRotation = clockRotation, nativeDrawing = { nativeEntry },
                        hidden = { entrance.value == 0f }, independentPhysicalOffset = {
                        Offset(0f, (1f - entrance.value) * travel)
                    }) {
                        PhoneLandscapeClock(playerVisible && contentActivated && motion.wantsOpen && rotation != 0 &&
                            !showLyrics && hasEntered,
                            canvasWidth, artwork, Modifier.fillMaxSize())
                    }
                }) { children, constraints ->
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    travel = (width + height).toFloat().coerceAtLeast(1f)
                    val clock = children.single().measure(Constraints.fixed(
                        width.coerceAtLeast(1), (height * .65f).roundToInt().coerceAtLeast(1)))
                    layout(width, height) {
                        clock.place((width * .69f).roundToInt() - clock.width / 2,
                            (height * .475f).roundToInt() - clock.height / 2)
                    }
                }
            }
        }
    }
}
