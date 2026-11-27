package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.max

// 横竖屏切换和手势收起共用同一临界阻尼弹簧，起步更柔和，并保持位置与速度连续。
internal const val PHONE_PLAYER_TURN_STIFFNESS = 160f

/** 元素的目标姿态（使用物理窗口坐标），与动画补偿值分开维护。 */
internal data class PhoneTurnPose(val pivot: Offset, val size: Size, val rotation: Float,
    val pivotFraction: Offset = Offset(.5f, .5f), val corner: Float = 0f) {
    fun bounds(): Rect {
        val topLeft = pivot - Offset(size.width * pivotFraction.x, size.height * pivotFraction.y)
        val points = listOf(topLeft, topLeft + Offset(size.width, 0f),
            topLeft + Offset(size.width, size.height), topLeft + Offset(0f, size.height))
            .map { turnRotate(it - pivot, rotation) + pivot }
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}

private fun turnRotate(vector: Offset, angle: Float): Offset {
    val radians = angle * Math.PI / 180.0
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    return Offset(vector.x * c - vector.y * s, vector.x * s + vector.y * c)
}

/** 把物理窗口坐标下的位移换算到元素本地坐标系，必须使用宿主当前实际旋转角度。 */
internal fun phoneTurnLocalOffset(physical: Offset, actualRotation: Float): Offset =
    turnRotate(physical, -actualRotation)

internal fun phoneTurnPoseResidual(current: PhoneTurnPose, target: PhoneTurnPose): Float {
    val position = (current.pivot - target.pivot).getDistance()
    val extent = max(abs(current.size.width - target.size.width), abs(current.size.height - target.size.height)) / 2f
    val radians = abs(current.rotation - target.rotation) * Math.PI.toFloat() / 180f
    return max(position + extent + radians * max(target.size.width, target.size.height) / 2f,
        abs(current.corner - target.corner))
}

private data class PhoneTurnDelta(val x: Float = 0f, val y: Float = 0f,
    val width: Float = 0f, val height: Float = 0f) {
    operator fun plus(other: PhoneTurnDelta) = PhoneTurnDelta(x + other.x, y + other.y,
        width + other.width, height + other.height)
    operator fun minus(other: PhoneTurnDelta) = PhoneTurnDelta(x - other.x, y - other.y,
        width - other.width, height - other.height)
}

private val TurnDeltaConverter = TwoWayConverter<PhoneTurnDelta, AnimationVector4D>(
    { AnimationVector4D(it.x, it.y, it.width, it.height) },
    { PhoneTurnDelta(it.v1, it.v2, it.v3, it.v4) },
)

private data class PhoneTurnSourceFrame(val pose: PhoneTurnPose?, val velocity: PhoneTurnDelta,
    val angularVelocity: Float, val cornerVelocity: Float)

private class PhoneTurnGroup {
    var base by mutableStateOf<PhoneTurnPose?>(null)
    val delta = Animatable(PhoneTurnDelta(), TurnDeltaConverter, PhoneTurnDelta(.1f, .1f, .1f, .1f))
    val angle = Animatable(0f)
    val corner = Animatable(0f, visibilityThreshold = .05f)
    var pendingPose by mutableStateOf<PhoneTurnPose?>(null)
    var resting by mutableStateOf(true)
    var preparedGeneration = -1
    var activeToken: Any? = null
    var endpointRotation = 0
    var registeredGeneration = -1
    var bindingValid by mutableStateOf(false)
    var refreshRotation = 0
    var refreshToken: Any? = null
    var refreshPose: (() -> Unit)? = null
    var job: Job? = null
    var handoffJob: Job? = null
    var cornerVelocity = 0f
    var capturedCornerVelocity = 0f
    var deltaVelocity = PhoneTurnDelta()
    var angleVelocity = 0f
    var capturedVelocity = PhoneTurnDelta()
    var capturedAngleVelocity = 0f
    var drawingDistance by mutableFloatStateOf(0f)
    var drawingGeneration by mutableIntStateOf(-1)
    var physicalOffset by mutableStateOf<(() -> Offset)?>(null)
    var physicalVelocity by mutableStateOf<(() -> Offset)?>(null)
    var scaleContent = true
    var uniformScale = false
    var hidden = false

    fun offsetVelocity(): PhoneTurnDelta = (physicalVelocity?.invoke() ?: Offset.Zero).let {
        PhoneTurnDelta(it.x, it.y)
    }

    fun effectiveBase(): PhoneTurnPose? = base?.let { pose ->
        pose.copy(pivot = pose.pivot + (physicalOffset?.invoke() ?: Offset.Zero))
    }

    fun sourceFrame(): PhoneTurnSourceFrame {
        val retained = handoffJob?.isActive == true || pendingPose != null
        return PhoneTurnSourceFrame(
            if (!hidden && (activeToken != null || retained)) current() else null,
            if (retained) capturedVelocity else deltaVelocity + offsetVelocity(),
            if (retained) capturedAngleVelocity else angleVelocity,
            if (retained) capturedCornerVelocity else cornerVelocity)
    }

    fun current(): PhoneTurnPose? {
        val pose = effectiveBase() ?: return null
        if (resting && pendingPose == null) return pose
        val d = delta.value
        val desired = pendingPose ?: pose.copy(pivot = pose.pivot + Offset(d.x, d.y),
            size = Size((pose.size.width + d.width).coerceAtLeast(.001f),
                (pose.size.height + d.height).coerceAtLeast(.001f)), rotation = pose.rotation + angle.value, corner = (pose.corner + corner.value).coerceAtLeast(0f))
        val size = when {
            !scaleContent -> pose.size
            uniformScale -> Size(pose.size.width * desired.size.height / pose.size.height.coerceAtLeast(.001f), desired.size.height)
            else -> desired.size
        }
        return desired.copy(size = size, pivotFraction = pose.pivotFraction)
    }
}

/** 只负责横竖屏切换时各元素的图层过渡动画，不接管整页开合、歌词展开或切歌动画。 */
@Stable
internal class PhonePlayerTurn(private val scope: CoroutineScope) {
    private val groups = linkedMapOf<String, PhoneTurnGroup>()
    private val layoutMotionKeys = mutableSetOf<String>()
    private var sourceFrame: Map<String, PhoneTurnSourceFrame>? = null
    private var sourceFrameGeneration = -1

    /** 在录制旧画面的同一帧采样各元素姿态，避免异步拷贝位图期间的新运动覆盖动画起点。 */
    internal fun captureSourceFrame() {
        sourceFrame = groups.mapValues { (_, group) -> group.sourceFrame() }
        sourceFrameGeneration = generation
    }

    private val layoutChangeListeners = mutableMapOf<Any, () -> Unit>()
    fun beforeLayoutChange(owner: Any, action: () -> Unit) { layoutChangeListeners[owner] = action }
    fun removeLayoutChangeListener(owner: Any) { layoutChangeListeners.remove(owner) }
    private fun prepareLayoutChange() { layoutChangeListeners.values.toList().forEach { it() } }
    var generation by mutableIntStateOf(0)
        private set
    private var held by mutableStateOf(false)
    private val holdOwners = mutableMapOf<Any, Boolean>()
    private val gestureOwner = Any()
    private var resetVelocityOnResume = false
    private var endpointRevision by mutableIntStateOf(0)
    private var lastFrameNanos = 0L
    private var heldFrameJob: Job? = null
    var targetRotation by mutableIntStateOf(0)
        private set
    var isTurning by mutableStateOf(false)
        private set
    var orientationChanging by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    // 预备阶段遮罩还没订阅弹簧状态，必须用 Compose State 标记来触发绘制节点读取最新动画。
    private var preparationStaged by mutableStateOf(false)
    var nativeFallback by mutableStateOf(false)
        private set

    fun basePose(key: String): PhoneTurnPose? = groups[key]?.effectiveBase()
    internal fun layoutPose(key: String): PhoneTurnPose? = groups[key]?.base
    fun currentPose(key: String): PhoneTurnPose? = groups[key]?.current()

    /** 只按转向本身的剩余位移计算进度，不把同时进行的歌词开合位移算进去。 */
    internal fun drawingProgress(key: String): Float {
        if (preparing || preparationStaged) return 0f
        if (!orientationChanging) return 1f
        val group = groups[key] ?: return 1f
        if (group.hidden || group.resting && group.pendingPose == null) return 1f
        if (group.drawingGeneration != generation) return 0f
        val target = group.effectiveBase() ?: return 0f
        val current = group.current() ?: return 0f
        return phoneTurnDrawingProgress(group.drawingDistance,
            phoneTurnPoseResidual(current.copy(size = target.size), target))
    }

    /** 正常开合动画接管布局后，允许转向弹簧自然收尾，但不再对开合引起的布局变化做反向补偿。 */
    fun followLayoutMotion(keys: Set<String>) {
        if (isTurning && layoutMotionKeys.addAll(keys)) endpointRevision++
    }
    internal fun drawingPose(key: String, base: PhoneTurnPose, physicalOffset: Offset): PhoneTurnPose? =
        when {
            preparing -> base
            // 非转向状态下直接用当前实际布局，避免带入上一轮转向残留的角度或位移。
            nativeFallback || !isTurning -> base.copy(pivot = base.pivot + physicalOffset)
            else -> currentPose(key)
        }

    fun refreshEndpoints(keys: Set<String>, rotation: Int) {
        keys.forEach { key -> groups[key]?.takeIf { it.refreshRotation == rotation }?.refreshPose?.invoke() }
    }

    /** 所有目标布局就绪后统一启动各元素动画，避免先测量完的元素抢跑。 */
    fun releasePreparation() {
        preparing = false
        preparationStaged = false
        lastFrameNanos = 0L
        groups.values.filter { !it.hidden && it.activeToken != null && it.bindingValid &&
            it.registeredGeneration == generation && it.endpointRotation == targetRotation &&
            it.preparedGeneration != generation }.forEach(::start)
    }

    /** 先把补偿到起点的目标子树画在旧画面下方等待验收，验证通过前先不推进弹簧。 */
    fun stagePreparation() {
        preparationStaged = true
        preparing = false
    }

    /** 准备超时时取消几何位移动画，改由上层旧画面做淡出过渡。 */
    fun finishPreparationWithoutMotion() {
        preparing = false
        preparationStaged = false
        nativeFallback = true
        groups.values.forEach { it.job?.cancel(); discardPending(it); it.preparedGeneration = generation }
        isTurning = false
        orientationChanging = false
    }

    internal fun diagnosticState(key: String): String = groups[key]?.let {
        "gen=$generation target=$targetRotation turning=$isTurning held=$held " +
            "token=${it.activeToken?.let(System::identityHashCode)} valid=${it.bindingValid} registered=${it.registeredGeneration} " +
            "endpoint=${it.endpointRotation} prepared=${it.preparedGeneration} pending=${it.pendingPose} " +
            "refreshRotation=${it.refreshRotation} refreshToken=${it.refreshToken?.let(System::identityHashCode)} " +
            "job=${it.job?.isActive} bridge=${it.handoffJob?.isActive} base=${it.base} current=${it.current()}"
    } ?: "gen=$generation target=$targetRotation absent"

    fun endpointReady(key: String, rotation: Int): Boolean {
        endpointRevision
        return groups[key]?.let { it.activeToken != null && it.bindingValid &&
            it.endpointRotation == rotation && it.registeredGeneration == generation } == true
    }

    /** 检测时钟动画是否卡在半路没人推进（正常转向中、被菜单暂停或准备中不算异常）。 */
    internal fun clockHandoffStalled(rotation: Int): Boolean {
        if (!isTurning || held || preparing) return false
        val clock = groups["clock"] ?: return false
        return !clock.hidden && clock.endpointRotation == rotation &&
            (clock.pendingPose != null || !clock.resting) &&
            clock.job?.isActive != true && clock.handoffJob?.isActive != true
    }

    internal fun endpointGeometry(keys: Set<String>, rotation: Int): Any? =
        if (keys.all { endpointReady(it, rotation) }) keys.map { it to basePose(it) } else null

    suspend fun awaitEndpoint(key: String, rotation: Int): Boolean {
        val epoch = generation
        PlayerMotionDiagnostics.event("endpoint-wait/$key") { "rotation=$rotation ${diagnosticState(key)}" }
        while (epoch == generation) {
            // 宿主仅修改图层旋转角度时未必触发重新测量，需每帧主动重读实际坐标。
            groups[key]?.takeIf { it.refreshRotation == rotation }?.refreshPose?.invoke()
            if (endpointReady(key, rotation)) {
                PlayerMotionDiagnostics.event("endpoint-ready/$key") { diagnosticState(key) }
                return true
            }
            PlayerMotionDiagnostics.sample("endpoint-pending/$key") { diagnosticState(key) }
            withFrameNanos { }
        }
        PlayerMotionDiagnostics.event("endpoint-aborted/$key") { "epoch=$epoch ${diagnosticState(key)}" }
        return false
    }

    /** 剩余位移不到半像素且速度很慢时视为已基本静止，避免附属控件空等亚像素尾段。 */
    fun hasVisibleMotion(keys: Set<String>): Boolean {
        endpointRevision
        return keys.any { key ->
        groups[key]?.let { group ->
            if (key in layoutMotionKeys || group.hidden || group.activeToken == null) false
            else if (!group.bindingValid || group.endpointRotation != targetRotation ||
                group.registeredGeneration != generation) group.pendingPose != null
            else {
                val current = group.current()
                val base = group.effectiveBase()
                val velocity = if (group.pendingPose != null) group.capturedVelocity else group.deltaVelocity
                val angular = if (group.pendingPose != null) group.capturedAngleVelocity else group.angleVelocity
                val speed = max(abs(velocity.x), abs(velocity.y)) +
                    max(abs(velocity.width), abs(velocity.height)) / 2f +
                    abs(angular) * Math.PI.toFloat() / 180f * max(base?.size?.width ?: 0f, base?.size?.height ?: 0f) / 2f
                current != null && base != null &&
                    (phoneTurnPoseResidual(current, base) > .5f || speed / 60f > .5f)
            }
        } == true
        }
    }

    suspend fun awaitElementsVisibleSettled(keys: Set<String>) {
        val epoch = generation
        while (epoch == generation && hasVisibleMotion(keys)) {
            if (held) snapshotFlow { !held }.first { it } else withFrameNanos { }
        }
    }

    fun reset(rotation: Int = 0) {
        layoutMotionKeys.clear()
        PlayerMotionDiagnostics.event("turn-reset") { "gen=$generation rotation=$rotation" }
        if (rotation != targetRotation) prepareLayoutChange()
        sourceFrame = null
        sourceFrameGeneration = -1
        heldFrameJob?.cancel()
        lastFrameNanos = 0L
        held = false
        holdOwners.clear()
        resetVelocityOnResume = false
        preparing = false
        preparationStaged = false
        nativeFallback = false
        generation++
        endpointRevision++
        groups.values.forEach { group ->
            group.job?.cancel()
            group.handoffJob?.cancel()
            group.cornerVelocity = 0f
            group.capturedCornerVelocity = 0f
            group.pendingPose = null
            group.resting = true
            group.deltaVelocity = PhoneTurnDelta()
            group.angleVelocity = 0f
            group.capturedVelocity = PhoneTurnDelta()
            group.capturedAngleVelocity = 0f
        }
        targetRotation = rotation
        isTurning = false
        orientationChanging = false
    }

    /** 手势开始时暂存当前姿态和速度，松手后从同一组弹簧继续动画。 */
    fun hold() = hold(gestureOwner, trackFrames = true)

    fun hold(owner: Any, trackFrames: Boolean = false) {
        holdOwners[owner] = trackFrames
        if (!trackFrames) resetVelocityOnResume = true
        if (held) {
            updateHeldFrames()
            return
        }
        if (!isTurning) lastFrameNanos = 0L
        held = true
        updateHeldFrames()
        isTurning = true
        groups.values.forEach { group ->
            val bridging = group.handoffJob?.isActive == true
            group.handoffJob?.cancel()
            group.pendingPose = group.current()
            // 惯性桥接阶段已经更新了衰减后的速度，不能再用旧弹簧的速度覆盖。
            if (!bridging) {
                group.capturedCornerVelocity = group.cornerVelocity
                group.capturedVelocity = group.deltaVelocity + group.offsetVelocity()
                group.capturedAngleVelocity = group.angleVelocity
            }
            group.job?.cancel()
        }
    }

    private fun updateHeldFrames() {
        if (holdOwners.values.none { it }) {
            heldFrameJob?.cancel()
            heldFrameJob = null
            return
        }
        if (heldFrameJob?.isActive != true) heldFrameJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (held) lastFrameNanos = withFrameNanos { it }
        }
    }

    fun resume() = resume(gestureOwner)

    fun resume(owner: Any) {
        val trackedFrames = holdOwners.remove(owner) ?: return
        if (!held) return
        if (holdOwners.isNotEmpty()) { updateHeldFrames(); return }
        heldFrameJob?.cancel()
        heldFrameJob = null
        // 菜单可能长时间停留，恢复时不把暂停时长算进弹簧第一帧。
        if (!trackedFrames || resetVelocityOnResume) lastFrameNanos = 0L
        if (resetVelocityOnResume) {
            // 菜单暂停期间画面已经静止，恢复时不重新释放几秒前保存的动量。
            groups.values.forEach { group ->
                group.capturedVelocity = PhoneTurnDelta()
                group.capturedAngleVelocity = 0f
                group.capturedCornerVelocity = 0f
                group.deltaVelocity = PhoneTurnDelta()
                group.angleVelocity = 0f
                group.cornerVelocity = 0f
            }
            resetVelocityOnResume = false
        }
        held = false
        groups.values.filter { it.pendingPose != null }.forEach { group ->
            // 如果松手时新方向的布局还没就绪，先继续惯性桥接，不要急着往旧布局位置回弹。
            if (group.activeToken != null && group.bindingValid &&
                group.registeredGeneration == generation && group.endpointRotation == targetRotation) start(group)
            else bridge(group)
        }
        if (groups.values.none { it.job?.isActive == true || it.handoffJob?.isActive == true ||
                it.activeToken != null && it.pendingPose != null }) {
            isTurning = false
            orientationChanging = false
        }
    }

    fun begin(targetRotation: Int, prepare: Boolean = false) {
        layoutMotionKeys.clear()
        PlayerMotionDiagnostics.event("turn-begin") { "gen=$generation from=${this.targetRotation} to=$targetRotation" }
        prepareLayoutChange()
        val recorded = sourceFrame.takeIf { prepare && sourceFrameGeneration == generation }
        sourceFrame = null
        sourceFrameGeneration = -1
        preparing = prepare
        preparationStaged = false
        nativeFallback = false
        if (!isTurning) lastFrameNanos = 0L
        orientationChanging = orientationChanging || targetRotation != this.targetRotation
        this.targetRotation = targetRotation
        generation++
        endpointRevision++
        isTurning = true
        groups.forEach { (key, group) ->
            val frame = recorded?.get(key) ?: group.sourceFrame()
            val source = frame.pose
            group.handoffJob?.cancel()
            group.pendingPose = source
            group.capturedVelocity = frame.velocity
            group.capturedAngleVelocity = frame.angularVelocity
            group.capturedCornerVelocity = frame.cornerVelocity
            group.job?.cancel()
            if (source == null) group.resting = true else bridge(group)
        }
    }

    /** 等新布局就绪的间隙先按惯性衰减继续滑动，避免画面突然定住；新布局到位后立刻交回弹簧。 */
    private fun bridge(group: PhoneTurnGroup) {
        if (held || preparing || preparationStaged || ExperiencePreferences.options.reduceMotion) return
        val epoch = generation
        // 用与弹簧相同的固有频率做速度衰减，交回弹簧时速度衔接更自然，不会突然急刹。
        val decayRate = sqrt(PHONE_PLAYER_TURN_STIFFNESS)
        group.handoffJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = lastFrameNanos
            while (isActive && epoch == generation && group.pendingPose != null) {
                val frame = withFrameNanos { it }
                val dt = if (previous == 0L) 0f else (frame - previous).coerceAtLeast(0L) / 1_000_000_000f
                previous = frame
                lastFrameNanos = frame
                val decay = exp(-decayRate * dt)
                val travel = (1f - decay) / decayRate
                val pose = group.pendingPose ?: break
                val velocity = group.capturedVelocity
                group.pendingPose = pose.copy(
                    pivot = pose.pivot + Offset(velocity.x * travel, velocity.y * travel),
                    size = Size((pose.size.width + velocity.width * travel).coerceAtLeast(.001f),
                        (pose.size.height + velocity.height * travel).coerceAtLeast(.001f)),
                    rotation = pose.rotation + group.capturedAngleVelocity * travel,
                    corner = (pose.corner + group.capturedCornerVelocity * travel).coerceAtLeast(0f))
                group.capturedVelocity = PhoneTurnDelta(velocity.x * decay, velocity.y * decay,
                    velocity.width * decay, velocity.height * decay)
                group.capturedAngleVelocity *= decay
                group.capturedCornerVelocity *= decay
            }
        }
    }

    internal fun attachEndpoint(key: String, token: Any, rotation: Int, refresh: () -> Unit) {
        PlayerMotionDiagnostics.changed("attach/$key") { "rotation=$rotation token=${System.identityHashCode(token)}" }
        val group = groups.getOrPut(key) { PhoneTurnGroup() }
        group.refreshToken = token
        group.refreshRotation = rotation
        group.refreshPose = refresh
    }

    /** 面板隐藏后立即清掉残留的转向状态，下次打开时直接走正常入场动画。 */
    internal fun updateHidden(key: String, token: Any, hidden: Boolean) {
        val group = groups[key]?.takeIf { it.refreshToken === token } ?: return
        if (group.hidden == hidden) return
        group.hidden = hidden
        if (hidden) {
            group.job?.cancel()
            discardPending(group)
            group.preparedGeneration = generation
        }
        endpointRevision++
    }

    internal fun register(key: String, token: Any, pose: PhoneTurnPose, endpointRotation: Int,
        physicalOffset: (() -> Offset)? = null, physicalVelocity: (() -> Offset)? = null,
        scaleContent: Boolean = true, uniformScale: Boolean = false,
        physicalRotation: Int? = null, hidden: Boolean = false) {
        val group = groups.getOrPut(key) { PhoneTurnGroup() }
        if (physicalRotation != null &&
            abs(((pose.rotation - physicalRotation + 540f) % 360f) - 180f) > .01f) {
            val wasValid = group.bindingValid
            group.bindingValid = false
            if (wasValid) endpointRevision++
            PlayerMotionDiagnostics.sample("register-rejected/$key") {
                "expected=$physicalRotation raw=$pose ${diagnosticState(key)}"
            }
            return
        }
        val previous = group.base
        val continuation = if (isTurning && key !in layoutMotionKeys && !preparing && !preparationStaged &&
            group.preparedGeneration == generation && previous != pose &&
            endpointRotation == targetRotation) group.sourceFrame() else null
        val registrationChanged = !group.bindingValid || group.registeredGeneration != generation ||
            group.activeToken !== token || group.endpointRotation != endpointRotation ||
            group.scaleContent != scaleContent || group.uniformScale != uniformScale || group.hidden != hidden
        group.bindingValid = true
        group.registeredGeneration = generation
        group.scaleContent = scaleContent
        group.uniformScale = uniformScale
        group.hidden = hidden
        group.physicalOffset = physicalOffset
        group.physicalVelocity = physicalVelocity
        group.activeToken = token
        group.endpointRotation = endpointRotation
        // 布局回调只是更新目标基准位置，不能拿回调间隔来算初速度。
        group.base = pose
        continuation?.pose?.let { current ->
            val velocity = phoneTurnReanchorVelocity(current, pose.pivotFraction,
                Offset(continuation.velocity.x, continuation.velocity.y),
                Offset(continuation.velocity.width, continuation.velocity.height), continuation.angularVelocity)
            group.job?.cancel()
            group.handoffJob?.cancel()
            group.pendingPose = phoneTurnReanchor(current, pose.pivotFraction)
            group.capturedVelocity = continuation.velocity.copy(x = velocity.x, y = velocity.y)
            group.capturedAngleVelocity = continuation.angularVelocity
            group.capturedCornerVelocity = continuation.cornerVelocity
            group.preparedGeneration = -1
        }
        if (registrationChanged || previous != pose) endpointRevision++
        PlayerMotionDiagnostics.sample("register/$key") { "hidden=$hidden ${diagnosticState(key)}" }
        if (hidden) {
            // 已隐藏的附属元素无需保留旧位置，直接清理，避免影响下次打开。
            group.job?.cancel()
            group.preparedGeneration = generation
            discardPending(group)
            return
        }
        if (!isTurning && group.pendingPose != null) discardPending(group)
        if (isTurning && !held && !preparing && endpointRotation == targetRotation && group.preparedGeneration != generation) start(group)
    }

    private fun discardPending(group: PhoneTurnGroup) {
        group.handoffJob?.cancel()
        group.pendingPose = null
        group.resting = true
        group.cornerVelocity = 0f
        group.capturedCornerVelocity = 0f
        group.deltaVelocity = PhoneTurnDelta()
        group.angleVelocity = 0f
        group.capturedVelocity = PhoneTurnDelta()
        group.capturedAngleVelocity = 0f
    }

    internal fun unregister(key: String, token: Any) {
        PlayerMotionDiagnostics.event("unregister/$key") { "token=${System.identityHashCode(token)} ${diagnosticState(key)}" }
        groups[key]?.let {
            if (it.activeToken === token) {
                // 旧组件销毁时解除绑定和偏移回调，防止旧回调影响新组件；交接姿态已保存在 pendingPose。
                it.activeToken = null
                it.bindingValid = false
                it.physicalOffset = null
                it.physicalVelocity = null
            }
            if (it.refreshToken === token) it.refreshPose = null
            endpointRevision++
        }
        // 切换横竖屏销毁旧分支时保留 group，让新分支的同名元素接续动画。
    }

    /** 动画中途改目标时复用上一帧时间戳作为起点，下一帧直接推进，避免在原地多停一帧。 */
    private suspend fun withTurnFrameClock(origin: Long, block: suspend () -> Unit) {
        val upstream = checkNotNull(coroutineContext[MonotonicFrameClock])
        val clock = object : MonotonicFrameClock {
            private var seed = origin > 0L
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                if (seed) { seed = false; return onFrame(origin) }
                return upstream.withFrameNanos { frame ->
                    lastFrameNanos = frame
                    onFrame(frame)
                }
            }
        }
        withContext(clock) { block() }
    }

    private fun start(group: PhoneTurnGroup) {
        if (preparing || preparationStaged || held) return
        val epoch = generation
        val base = group.effectiveBase() ?: return
        val source = group.pendingPose ?: return
        if (group.drawingGeneration != epoch) {
            group.drawingDistance = phoneTurnPoseResidual(source.copy(size = base.size), base)
            group.drawingGeneration = epoch
        }
        group.handoffJob?.cancel()
        group.preparedGeneration = epoch
        val start = PhoneTurnDelta(source.pivot.x - base.pivot.x, source.pivot.y - base.pivot.y,
            if (group.scaleContent && !group.uniformScale) source.size.width - base.size.width else 0f,
            if (group.scaleContent) source.size.height - base.size.height else 0f)
        val startAngle = source.rotation - base.rotation
        val startCorner = source.corner - base.corner
        val cornerVelocity = group.capturedCornerVelocity
        val rawVelocity = group.capturedVelocity - group.offsetVelocity()
        val velocity = rawVelocity.copy(
            width = if (group.scaleContent && !group.uniformScale) rawVelocity.width else 0f,
            height = if (group.scaleContent) rawVelocity.height else 0f)
        val angularVelocity = group.capturedAngleVelocity
        if (start == PhoneTurnDelta() && startAngle == 0f && velocity == PhoneTurnDelta() && angularVelocity == 0f && startCorner == 0f && cornerVelocity == 0f) {
            discardPending(group)
            return
        }
        val startFrame = lastFrameNanos
        group.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // 在各轴 Animatable 同步到起点之前保留 pendingPose，避免新布局首帧闪现到终点。
            group.delta.snapTo(start)
            group.angle.snapTo(startAngle)
            group.corner.snapTo(startCorner)
            group.resting = false
            group.pendingPose = null
            if (ExperiencePreferences.options.reduceMotion) {
                group.delta.snapTo(PhoneTurnDelta())
                group.angle.snapTo(0f)
                group.corner.snapTo(0f)
            } else coroutineScope {
                if (startCorner != 0f || cornerVelocity != 0f) launch(start = CoroutineStart.UNDISPATCHED) {
                    withTurnFrameClock(startFrame) {
                        group.corner.animateTo(0f, musicSpring(stiffness = PHONE_PLAYER_TURN_STIFFNESS, visibilityThreshold = .05f),
                            initialVelocity = cornerVelocity) { group.cornerVelocity = this.velocity }
                    }
                    group.cornerVelocity = 0f
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    withTurnFrameClock(startFrame) {
                        group.delta.animateTo(PhoneTurnDelta(), musicSpring(stiffness = PHONE_PLAYER_TURN_STIFFNESS,
                            visibilityThreshold = PhoneTurnDelta(.1f, .1f, .1f, .1f)), initialVelocity = velocity) {
                            group.deltaVelocity = this.velocity
                        }
                    }
                    group.deltaVelocity = PhoneTurnDelta()
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    withTurnFrameClock(startFrame) {
                        group.angle.animateTo(0f, musicSpring(stiffness = PHONE_PLAYER_TURN_STIFFNESS,
                            visibilityThreshold = .05f / maxOf(base.size.width, base.size.height, 1f) * 180f / Math.PI.toFloat()),
                            initialVelocity = angularVelocity) {
                            group.angleVelocity = this.velocity
                        }
                    }
                    group.angleVelocity = 0f
                }
            }
            if (generation == epoch) {
                group.pendingPose = null
                group.resting = true
                group.cornerVelocity = 0f
                group.deltaVelocity = PhoneTurnDelta()
                group.angleVelocity = 0f
            }
        }
    }

    /** 只等待指定的核心元素动画结束，不被其他无关面板拖住。 */
    suspend fun awaitElementsSettled(keys: Set<String>) {
        val epoch = generation
        while (epoch == generation && keys.any { key ->
            groups[key]?.let { group ->
                group.activeToken != null && group.bindingValid &&
                    (group.pendingPose != null || !group.resting || group.job?.isActive == true)
            } == true
        }) {
            if (held) snapshotFlow { !held }.first { it } else withFrameNanos { }
        }
    }

    suspend fun awaitSettled() {
        val epoch = generation
        repeat(2) { withFrameNanos { } }
        if (epoch != generation) return
        while (epoch == generation) {
            if (held) { snapshotFlow { !held }.first { it }; continue }
            // 只刷新尚未就绪的元素坐标，已就绪的由布局回调自然更新，避免每帧重算整页矩阵。
            groups.values.toList().filter { it.refreshRotation == targetRotation &&
                (!it.bindingValid || it.registeredGeneration != epoch || it.endpointRotation != targetRotation)
            }.forEach { it.refreshPose?.invoke() }
            groups.values.filter { !it.hidden && !it.resting && it.pendingPose == null &&
                it.job?.isActive != true && it.activeToken != null && it.bindingValid &&
                it.registeredGeneration == epoch && it.endpointRotation == targetRotation }.forEach {
                // 切歌导致旧动画协程被取消时，从当前实际姿态重新起跑，避免元素卡在半路。
                val frame = it.sourceFrame()
                it.pendingPose = frame.pose
                it.capturedVelocity = frame.velocity
                it.capturedAngleVelocity = frame.angularVelocity
                it.capturedCornerVelocity = frame.cornerVelocity
                it.preparedGeneration = -1
            }
            groups.values.toList().filter {
                !it.hidden && it.activeToken != null && it.bindingValid && it.registeredGeneration == epoch &&
                    it.endpointRotation == targetRotation && it.preparedGeneration != epoch
            }.forEach(::start)
            val running = groups.values.any { it.job?.isActive == true }
            val waitingForLayout = groups.values.any {
                !it.hidden && it.pendingPose != null && it.activeToken != null && it.refreshToken != null && it.refreshRotation == targetRotation &&
                    (!it.bindingValid || it.registeredGeneration != epoch || it.endpointRotation != targetRotation)
            }
            if (!running) {
                if (!waitingForLayout) break
                withFrameNanos { }
                continue
            }
            // 按帧轮询而不是 join 单个协程，方便切歌重绑时及时接续被取消的其他元素。
            withFrameNanos { }
        }
        if (epoch == generation) {
            // 处于关闭状态的面板可能从未触发布局，转向结束后顺手清理残留的 pendingPose。
            groups.values.forEach {
                if (it.pendingPose != null) discardPending(it)
                it.preparedGeneration = epoch
            }
            isTurning = false
            orientationChanging = false
            PlayerMotionDiagnostics.event("turn-settled") { "gen=$generation rotation=$targetRotation" }
        }
    }
}

internal val LocalPhonePlayerTurn = staticCompositionLocalOf<PhonePlayerTurn?> { null }

/** 参与横竖屏平滑过渡的元素容器：外层记录目标布局坐标，内层通过 graphicsLayer 做位移/旋转/缩放补偿。 */
@Composable
internal fun PhoneTurnElement(key: String, modifier: Modifier = Modifier,
    pivotFraction: Offset = Offset(.5f, .5f),
    pivotForSize: ((Size) -> Offset)? = null,
    onBasePose: ((PhoneTurnPose) -> Unit)? = null,
    cornerRadius: (() -> Float)? = null,
    physicalRotation: Int? = null,
    hidden: (() -> Boolean)? = null,
    physicalOffset: (() -> Offset)? = null,
    physicalVelocity: (() -> Offset)? = null,
    independentPhysicalOffset: (() -> Offset)? = null,
    nativeDrawing: (() -> Boolean)? = null,
    scaleContent: Boolean = true,
    uniformScale: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val turn = LocalPhonePlayerTurn.current
    val endpointRotation = LocalPhonePlayerRotation.current
    val latestPivot by rememberUpdatedState(pivotForSize)
    val latestCorner by rememberUpdatedState(cornerRadius)
    val expectedRotation = physicalRotation ?: endpointRotation.takeIf { key in PhonePlayerPresentation.BodyKeys }
    val latestPhysicalRotation by rememberUpdatedState(expectedRotation)
    val latestIndependentOffset by rememberUpdatedState(independentPhysicalOffset)
    val latestNativeDrawing by rememberUpdatedState(nativeDrawing)
    val latestHidden by rememberUpdatedState(hidden)
    val latestBaseCallback by rememberUpdatedState(onBasePose)
    val latestPhysicalOffset by rememberUpdatedState(physicalOffset)
    val latestPhysicalVelocity by rememberUpdatedState(physicalVelocity)
    // 在组合阶段监听隐藏状态，即使面板没走布局也能及时清理转向状态。
    val hiddenNow = remember { derivedStateOf { latestHidden?.invoke() == true } }.value
    fun pose(coordinates: LayoutCoordinates): PhoneTurnPose? = coordinates.turnPose(
        latestPivot?.invoke(Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())) ?: pivotFraction)
    if (turn == null) {
        Box(modifier.then(if (onBasePose == null) Modifier else Modifier.onGloballyPositioned {
            pose(it)?.let { base -> latestBaseCallback?.invoke(base) }
        }), propagateMinConstraints = true, content = content)
        return
    }
    val token = remember(turn, key) { Any() }
    val coordinatesHolder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val poseHolder = remember { arrayOfNulls<PhoneTurnPose>(1) }
    fun register(coordinates: LayoutCoordinates) {
        if (key == "clock" || key == "lyrics") PlayerMotionDiagnostics.sample("coordinates/$key") {
            "expected=$latestPhysicalRotation endpoint=$endpointRotation " + phoneCoordinateDiagnostic(coordinates)
        }
        pose(coordinates)?.let { raw ->
            poseHolder[0] = raw
            val it = raw.copy(corner = (latestCorner?.invoke() ?: 0f) *
                raw.size.width / coordinates.size.width.coerceAtLeast(1))
            turn.register(key, token, it, endpointRotation,
                physicalOffset = { latestPhysicalOffset?.invoke() ?: Offset.Zero },
                physicalVelocity = { latestPhysicalVelocity?.invoke() ?: Offset.Zero },
                scaleContent = scaleContent, uniformScale = uniformScale,
                physicalRotation = latestPhysicalRotation, hidden = latestHidden?.invoke() == true)
            latestBaseCallback?.invoke(turn.basePose(key) ?: it)
        }
    }
    SideEffect {
        turn.attachEndpoint(key, token, endpointRotation) { coordinatesHolder[0]?.let(::register) }
        turn.updateHidden(key, token, hiddenNow)
    }
    DisposableEffect(turn, key, token) { onDispose { turn.unregister(key, token) } }
    val placement = Modifier.onPlaced { coordinates ->
        coordinatesHolder[0] = coordinates
        register(coordinates)
    }
    Box(modifier.then(placement).onGloballyPositioned { coordinates ->
        coordinatesHolder[0] = coordinates
        register(coordinates)
    }, propagateMinConstraints = true) {
        Box(Modifier.graphicsLayer {
            // 动画帧直接读取缓存的基准姿态，避免每帧遍历坐标矩阵。
            val base = poseHolder[0] ?: turn.layoutPose(key)
            if (base == null) {
                PlayerMotionDiagnostics.changed("layer-missing/$key") { "base ${turn.diagnosticState(key)}" }
                return@graphicsLayer
            }
            val current = if (latestNativeDrawing?.invoke() == true) base
                else turn.drawingPose(key, base, latestPhysicalOffset?.invoke() ?: Offset.Zero)
            if (current == null) {
                PlayerMotionDiagnostics.changed("layer-missing/$key") { "current ${turn.diagnosticState(key)}" }
                return@graphicsLayer
            }
            val local = phoneTurnLocalOffset(current.pivot - base.pivot +
                (latestIndependentOffset?.invoke() ?: Offset.Zero), base.rotation)
            transformOrigin = TransformOrigin(base.pivotFraction.x, base.pivotFraction.y)
            translationX = local.x * size.width / base.size.width.coerceAtLeast(.001f)
            translationY = local.y * size.height / base.size.height.coerceAtLeast(.001f)
            scaleX = if (scaleContent) current.size.width / base.size.width.coerceAtLeast(.001f) else 1f
            scaleY = if (scaleContent) current.size.height / base.size.height.coerceAtLeast(.001f) else 1f
            rotationZ = current.rotation - base.rotation
            if (key == "clock" || key == "lyrics") PlayerMotionDiagnostics.sample("layer/$key") {
                "token=${System.identityHashCode(token)} raw=$base pose=$current " +
                    "translation=$translationX,$translationY rotation=$rotationZ scale=$scaleX,$scaleY " +
                    turn.diagnosticState(key)
            }
            // 不在元素层裁剪，边缘交给歌词自身的渐隐遮罩和物理窗口处理。
            clip = false
        }, propagateMinConstraints = true, content = content)
    }
}

private fun LayoutCoordinates.turnPose(pivotFraction: Offset): PhoneTurnPose? {
    if (!isAttached || size.width <= 0 || size.height <= 0) return null
    val origin = playerPointInRoot(Offset.Zero)
    val x = playerPointInRoot(Offset(size.width.toFloat(), 0f)) - origin
    val y = playerPointInRoot(Offset(0f, size.height.toFloat())) - origin
    val pivot = playerPointInRoot(Offset(size.width * pivotFraction.x, size.height * pivotFraction.y))
    if (!pivot.x.isFinite() || !pivot.y.isFinite()) return null
    return PhoneTurnPose(pivot, Size(hypot(x.x, x.y), hypot(y.x, y.y)),
        (atan2(x.y, x.x) * 180f / Math.PI.toFloat()), pivotFraction)
}
