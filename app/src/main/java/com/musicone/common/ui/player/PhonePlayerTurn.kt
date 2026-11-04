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

// 旋转与手势侧条交接共用临界阻尼弹簧，降低起速力度，保持原位置和速度连续。
internal const val PHONE_PLAYER_TURN_STIFFNESS = 160f

/** 锚点使用物理窗口坐标；动画补偿与原页面布局分别保存。 */
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

/** 物理位移只按实际宿主坐标换算，不能套用上一轮缓存的方向。 */
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

/** 只协调转向图层，不接管整页开合、歌词开合或切歌动画。 */
@Stable
internal class PhonePlayerTurn(private val scope: CoroutineScope) {
    private val groups = linkedMapOf<String, PhoneTurnGroup>()
    private val layoutMotionKeys = mutableSetOf<String>()
    private var sourceFrame: Map<String, PhoneTurnSourceFrame>? = null
    private var sourceFrameGeneration = -1

    /** 与旧画面录制同帧取样，异步拷贝位图期间的后续运动不能覆盖交接起点。 */
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
    // 起点验收时遮罩尚未订阅弹簧，释放标志本身必须能通知绘制节点继续读取动画。
    private var preparationStaged by mutableStateOf(false)
    var nativeFallback by mutableStateOf(false)
        private set

    fun basePose(key: String): PhoneTurnPose? = groups[key]?.effectiveBase()
    internal fun layoutPose(key: String): PhoneTurnPose? = groups[key]?.base
    fun currentPose(key: String): PhoneTurnPose? = groups[key]?.current()

    /** 仅计算转向补偿的收敛，不把同时发生的歌词开合位移计入渐隐进度。 */
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

    /** 正常开合接管布局后，保留转向弹簧尾段，但不再把开合的每帧布局反向补偿回去。 */
    fun followLayoutMotion(keys: Set<String>) {
        if (isTurning && layoutMotionKeys.addAll(keys)) endpointRevision++
    }
    internal fun drawingPose(key: String, base: PhoneTurnPose, physicalOffset: Offset): PhoneTurnPose? =
        when {
            preparing -> base
            // 普通开关只使用本次实际布局，不能重放上一轮转向残留的角度或位移。
            nativeFallback || !isTurning -> base.copy(pivot = base.pivot + physicalOffset)
            else -> currentPose(key)
        }

    fun refreshEndpoints(keys: Set<String>, rotation: Int) {
        keys.forEach { key -> groups[key]?.takeIf { it.refreshRotation == rotation }?.refreshPose?.invoke() }
    }

    /** 准备完成后统一释放各元素，禁止先登记的元素提前单独起步。 */
    fun releasePreparation() {
        preparing = false
        preparationStaged = false
        lastFrameNanos = 0L
        groups.values.filter { !it.hidden && it.activeToken != null && it.bindingValid &&
            it.registeredGeneration == generation && it.endpointRotation == targetRotation &&
            it.preparedGeneration != generation }.forEach(::start)
    }

    /** 先在旧帧下面显示补偿后的真实起点，验证完成之前不推进任何弹簧。 */
    fun stagePreparation() {
        preparationStaged = true
        preparing = false
    }

    /** 超时只取消几何补偿，保留本轮目标绑定，由上层旧画面完成交叉渐变。 */
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

    /** 只识别时钟确实无人推进的残留补偿，正常转向、菜单持有和预备均不判为丢失。 */
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
            // 宿主只改图层角度时未必重测，按帧重读实际坐标，不能永远等旧登记通知。
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

    /** 半像素以内且下一屏幕帧不会明显移动时，附属入口无需再等亚像素尾段。 */
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

    /** 手势保存正在显示的姿态和速度，松手后仍从同一组弹簧接续。 */
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
            // 桥接已衰减当前速度，不能再用取消前旧弹簧的速度覆盖。
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
            // 松手早于新布局时继续桥接，不能向旧方向的 base 提前起步。
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

    /** 新端点尚未登记时延续惯性，不把最后一帧静止挂住；登记后立即交回原弹簧。 */
    private fun bridge(group: PhoneTurnGroup) {
        if (held || preparing || preparationStaged || ExperiencePreferences.options.reduceMotion) return
        val epoch = generation
        // 等布局的短桥接使用同一固有频率衰减，交回弹簧时不另加一段急刹。
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

    /** 未放置的关闭面板也及时撤下旧转向，重新打开时只播放自身入场。 */
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
        // 布局登记不是运动采样；回调之间的时间差不能被换算成弹簧初速度。
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
            // 完全隐藏的附属元素没有需要保留的视觉旧端点，不让离屏转向拖住下一次入口。
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
                // 旧实例的时钟位移不能继续作用于重建实例；交接姿态已经另存于 pendingPose。
                it.activeToken = null
                it.bindingValid = false
                it.physicalOffset = null
                it.physicalVelocity = null
            }
            if (it.refreshToken === token) it.refreshPose = null
            endpointRevision++
        }
        // 横竖分支销毁时保留上一帧，供同名目标元素接续。
    }

    /** 重定向共用上一真实帧作为零时刻，下一帧直接推进，避免重新提交一帧静止起点。 */
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
            // pendingPose 在姿态各轴初始化完成前遮住调度间隙，新布局首帧不露出目标端点。
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

    /** 时钟等附属元素只等待自身依赖的可见主体，不被无关面板的弹簧拖住。 */
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
            // 只补登记失效的端点；有效端点由真实布局回调更新，不在每帧重读整页矩阵。
            groups.values.toList().filter { it.refreshRotation == targetRotation &&
                (!it.bindingValid || it.registeredGeneration != epoch || it.endpointRotation != targetRotation)
            }.forEach { it.refreshPose?.invoke() }
            groups.values.filter { !it.hidden && !it.resting && it.pendingPose == null &&
                it.job?.isActive != true && it.activeToken != null && it.bindingValid &&
                it.registeredGeneration == epoch && it.endpointRotation == targetRotation }.forEach {
                // 切歌重绑期间若旧任务已取消，从实际姿态续接，不能把半途补偿永久挂住。
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
            // 不等待某一个旧任务：切歌重绑期间每帧都能接续其他已取消的元素和时钟。
            withFrameNanos { }
        }
        if (epoch == generation) {
            // 关闭的目标面板可能从未放置；不能让旧端点挂到下次普通开关请求。
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

/** 外层保存原始布局姿态，内层仅补偿绘制；固定尺寸及文字自然包裹均沿用原约束。 */
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
    // 在组合阶段观察“完全隐藏”这一边界，未放置的面板也能提交撤下通知。
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
            // 布局登记和端点交接已保存真实宿主姿态；动画帧只读缓存，不再遍历父子矩阵。
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
            // 歌词保持字形比例，沿阅读焦点运动；边缘继续由原歌词渐隐和物理窗口裁剪。
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
