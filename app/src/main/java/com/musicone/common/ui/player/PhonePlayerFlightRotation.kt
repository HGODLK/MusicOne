package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/** 只给现有飞行层补偿侧边源条，页面进度仍完全由 PageMotion 持有。 */
@Stable
internal class PhonePlayerFlightRotation(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
    private val settling = Animatable(0f, visibilityThreshold = .00005f)
    private var travelPx = 1f
    private var turn: PhonePlayerTurn? = null
    var handoffActive by mutableStateOf(false)
        private set
    private var held by mutableStateOf(false)
    private var finishing by mutableStateOf(false)
    private var heldWeight by mutableFloatStateOf(0f)
    private var dragOriginProgress = 1f
    private var dragOriginWeight = 1f
    private var sideVelocity = 0f
    private var heldRotation = 0
    val sideWeight: Float get() = when {
        held -> heldWeight
        finishing -> settling.value
        else -> 0f
    }

    fun updateTravel(pixels: Float) { travelPx = pixels.coerceAtLeast(1f) }

    fun beginTurnDrag(turn: PhonePlayerTurn) {
        this.turn = turn
        handoffActive = true
        turn.hold()
    }

    fun resumeTurn() { turn?.resume() }

    fun targetPose(key: String): PhoneTurnPose? = if (handoffActive)
        turn?.currentPose(if (key == "subtitle") "artist" else key) else null

    suspend fun awaitTurn() { turn?.takeIf { it.isTurning }?.awaitSettled() }

    fun prepare(@Suppress("UNUSED_PARAMETER") rotation: Int) { complete() }

    /** 旧结算被接管时封存正在显示的补偿，不重置到新的侧条端点。 */
    fun sourceRotation(fallback: Int): Int = if (handoffActive) heldRotation else fallback

    fun beginDrag(rotation: Int, progress: Float = 1f) {
        val visible = sideWeight
        var initialWeight = visible
        if (progress >= .9999f || !handoffActive) {
            val angle = turn?.currentPose("cover")?.rotation ?: rotation.toFloat()
            val side = phonePlayerFlightHeldSide(rotation, angle)
            heldRotation = side.first
            if (progress >= .9999f) initialWeight = side.second
        }
        if (progress >= .9999f) sideVelocity = 0f
        // 当前倾角只生成等量补偿，不把接近竖屏的一点残差放大成整段 90 度。
        heldWeight = initialWeight
        dragOriginProgress = progress
        dragOriginWeight = heldWeight
        held = true
        finishing = false
    }

    fun dragTo(progress: Float) {
        if (!held) return
        // 中途接管先保住当前画面，继续收到零进度时再逐渐接到侧边竖条。
        val fraction = ((dragOriginProgress - progress) / dragOriginProgress.coerceAtLeast(.0001f)).coerceIn(0f, 1f)
        heldWeight = motionLerp(dragOriginWeight, 1f, fraction)
    }

    suspend fun settle() {
        if (!held && !finishing) return
        val visible = sideWeight
        val retainedVelocity = sideVelocity
        settling.snapTo(visible)
        finishing = true
        held = false
        if (ExperiencePreferences.options.reduceMotion) settling.snapTo(0f)
        else settling.animateTo(0f, musicSpring(stiffness = PHONE_PLAYER_TURN_STIFFNESS, visibilityThreshold = .05f / travelPx), initialVelocity = retainedVelocity) {
            sideVelocity = velocity
        }
        sideVelocity = 0f
        finishing = false
    }

    fun complete() { turn?.resume(); turn = null; handoffActive = false; heldRotation = 0; held = false; finishing = false; heldWeight = 0f; sideVelocity = 0f }
}

internal val LocalPhonePlayerFlightRotation = staticCompositionLocalOf<PhonePlayerFlightRotation?> { null }

internal data class PhonePlayerFlightGeometry(val bounds: Rect, val rotation: Float)

/** 外接框只用于定位，横屏文字等内容仍按其未旋转的真实尺寸绘制。 */
internal fun phonePlayerFlightTarget(bounds: Rect, rotation: Int): Rect = if (rotation == 0) bounds else
    Rect(bounds.center - Offset(bounds.height / 2f, bounds.width / 2f), Size(bounds.height, bounds.width))

internal fun phonePlayerFlightRotate(point: Offset, center: Offset, rotation: Float): Offset {
    val radians = rotation * Math.PI / 180.0
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val v = point - center
    return center + Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

internal fun phonePlayerFlightBounds(bounds: Rect, rotation: Float): Rect {
    if (rotation == 0f) return bounds
    val radians = rotation * Math.PI / 180.0
    val width = abs(cos(radians)).toFloat() * bounds.width + abs(sin(radians)).toFloat() * bounds.height
    val height = abs(sin(radians)).toFloat() * bounds.width + abs(cos(radians)).toFloat() * bounds.height
    return Rect(bounds.center - Offset(width / 2f, height / 2f), Size(width, height))
}

internal fun phonePlayerFlightSource(motion: PageMotion, flight: PhonePlayerFlightRotation?,
    rotation: Int, key: String, source: Rect): PhonePlayerFlightGeometry {
    val weight = flight?.sideWeight ?: 0f
    val sourceRotation = flight?.sourceRotation(rotation) ?: rotation
    if (sourceRotation == 0 || weight == 0f) return PhonePlayerFlightGeometry(source, 0f)
    val host = motion.hostBounds
    val local = source.translate(-host.left, -host.top)
    val ratio = host.height / host.width.coerceAtLeast(1f)
    val origin = Offset((host.width - host.height) / 2f, (host.height - host.width) / 2f)
    val center = Offset(local.center.x * ratio, host.width - (host.height - local.center.y)) + origin
    val sideCenter = phonePlayerFlightRotate(center, host.size.let { Offset(it.width / 2f, it.height / 2f) }, sourceRotation.toFloat()) + host.topLeft
    val sideWidth = if (key == "surface") source.width * ratio else source.width
    val side = Rect(sideCenter - Offset(sideWidth / 2f, source.height / 2f), Size(sideWidth, source.height))
    return PhonePlayerFlightGeometry(motionRect(source, side, weight), sourceRotation * weight)
}

/** 视觉覆盖补偿：倾斜窗口的本地尺寸覆盖整窗，接管首帧不因旋转裁掉四角。 */
internal fun phonePlayerFlightSurfacePose(host: Rect, angle: Float): PhoneTurnPose {
    val size = when (angle) {
        0f -> host.size
        90f, -90f -> Size(host.height, host.width)
        else -> {
            val radians = angle * Math.PI / 180.0
            val c = abs(cos(radians)).toFloat()
            val s = abs(sin(radians)).toFloat()
            Size(host.width * c + host.height * s, host.width * s + host.height * c)
        }
    }
    return PhoneTurnPose(host.center, size, angle)
}

internal fun phonePlayerFlightGeometry(motion: PageMotion, flight: PhonePlayerFlightRotation?,
    rotation: Int, key: String, source: Rect, target: Rect,
    surfaceFrame: PlayerSurfaceFlightFrame? = null): PhonePlayerFlightGeometry {
    val livePose = if (key == "surface" && flight?.handoffActive == true) {
        flight.targetPose("cover")?.let { phonePlayerFlightSurfacePose(motion.hostBounds, it.rotation) }
    } else flight?.targetPose(key)
    if (livePose != null) {
        // 实际枢轴可能是歌词焦点；换成等价中心矩形后再交给原飞行层。
        val center = phonePlayerFlightRotate(
            livePose.pivot + Offset(livePose.size.width * (.5f - livePose.pivotFraction.x),
                livePose.size.height * (.5f - livePose.pivotFraction.y)), livePose.pivot, livePose.rotation)
        var destination = Rect(center - Offset(livePose.size.width / 2f, livePose.size.height / 2f), livePose.size)
        val captured = motion.targetSnapshot[key]?.bounds
        if (captured != null && captured != target) {
            // 中途切歌的文字宽高仍由原独立链控制，只把其局部补偿带到当前倾斜姿态。
            val nativeCaptured = phonePlayerFlightTarget(captured, rotation)
            val nativeTarget = phonePlayerFlightTarget(target, rotation)
            val scaleX = livePose.size.width / nativeCaptured.width.coerceAtLeast(.001f)
            val scaleY = livePose.size.height / nativeCaptured.height.coerceAtLeast(.001f)
            val offset = phonePlayerFlightRotate(target.center - captured.center, Offset.Zero, -rotation.toFloat())
            val adjustedCenter = center + phonePlayerFlightRotate(Offset(offset.x * scaleX, offset.y * scaleY),
                Offset.Zero, livePose.rotation)
            val adjustedSize = Size(nativeTarget.width * scaleX, nativeTarget.height * scaleY)
            destination = Rect(adjustedCenter - Offset(adjustedSize.width / 2f, adjustedSize.height / 2f), adjustedSize)
        }
        val origin = phonePlayerFlightSource(motion, flight, rotation, key, source)
        return playerFlightContentGeometry(motion, flight, rotation, key, source, destination,
            PhonePlayerFlightGeometry(motionRect(origin.bounds, destination, motion.value),
                motionLerp(origin.rotation, livePose.rotation, motion.value)), surfaceFrame)
    }
    if ((rotation == 0 && flight?.handoffActive != true) || flight == null) return PhonePlayerFlightGeometry(motionRect(source, target, motion.value), 0f)
    val start = phonePlayerFlightSource(motion, flight, rotation, key, source)
    val destination = phonePlayerFlightTarget(target, rotation)
    return playerFlightContentGeometry(motion, flight, rotation, key, source, destination,
        PhonePlayerFlightGeometry(motionRect(start.bounds, destination, motion.value),
            motionLerp(start.rotation, rotation.toFloat(), motion.value)), surfaceFrame)
}

internal fun phonePlayerFlightArtworkCorner(sourceCornerPx: Float, targetCornerPx: Float,
    progress: Float, livePose: PhoneTurnPose?): Float =
    motionLerp(sourceCornerPx, livePose?.corner ?: targetCornerPx, progress)

internal fun phonePlayerFlightHeldSide(targetRotation: Int, visibleAngle: Float): Pair<Int, Float> {
    val side = if (targetRotation != 0) targetRotation else when {
        visibleAngle > 0f -> 90
        visibleAngle < 0f -> -90
        else -> 0
    }
    return side to (abs(visibleAngle) / 90f).coerceIn(0f, 1f)
}
