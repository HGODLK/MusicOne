package com.musicone

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 只发布确认后的方向和资格，原始传感器采样不进入界面状态。 */
internal data class PhonePlayerNativeOrientation(val rotationDegrees: Int = 0, val ready: Boolean = false) {
    val openingRotation: Int get() = if (ready) rotationDegrees else 0
}

internal data class PhonePlayerGravityPose(val orientation: Int, val inclination: Float)

/** 方向沿用 Android 自然坐标约定；屏幕正反面平放都按同一水平夹角处理。 */
internal fun phonePlayerGravityPose(x: Float, y: Float, z: Float): PhonePlayerGravityPose? {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
    val magnitude = sqrt(x * x + y * y + z * z)
    if (!magnitude.isFinite() || magnitude < .001f) return null
    val inclination = Math.toDegrees(acos((abs(z) / magnitude).coerceIn(0f, 1f)).toDouble()).toFloat()
    val orientation = ((Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).roundToInt() % 360) + 360) % 360
    return PhonePlayerGravityPose(orientation, inclination)
}

/** 内置手机页独立收紧，不改变音源自定义页原有方向策略。 */
internal fun phonePlayerStrictRotation(orientation: Int): Int? = when (orientation) {
    in 75..105 -> -90
    in 255..285 -> 90
    in 0..15, in 345..359, in 165..195 -> 0
    else -> null
}

internal class PhonePlayerNativeOrientationGate(initialRotation: Int = 0) {
    var state = PhonePlayerNativeOrientation(initialRotation)
        private set
    var decisionAtMs: Long? = null
        private set
    private var raised = false
    private var candidate: Int? = null

    fun reset(rotation: Int = state.rotationDegrees) {
        raised = false
        cancelCandidate()
        state = PhonePlayerNativeOrientation(rotation)
    }

    fun update(orientation: Int, inclination: Float, nowMs: Long): PhonePlayerNativeOrientation {
        raised = inclination.isFinite() && inclination in 0f..90f &&
            if (raised) inclination > 30f else inclination >= 45f
        val target = if (raised) phonePlayerStrictRotation(orientation) else null
        if (target == null) {
            cancelCandidate()
        } else if (candidate != target) {
            candidate = target
            state = state.copy(ready = false)
            decisionAtMs = nowMs + if (target == 0) 300L else 200L
        }
        return settle(nowMs)
    }

    fun settle(nowMs: Long): PhonePlayerNativeOrientation {
        val deadline = decisionAtMs
        if (deadline != null && nowMs >= deadline) {
            state = PhonePlayerNativeOrientation(requireNotNull(candidate), ready = true)
            decisionAtMs = null
        }
        return state
    }

    private fun cancelCandidate() {
        candidate = null
        decisionAtMs = null
        state = state.copy(ready = false)
    }
}

/** 没有重力传感器时低通估算，使用采样间隔而非固定帧率。 */
internal class PhonePlayerGravityFilter {
    private var timestamp: Long? = null
    private val gravity = FloatArray(3)

    fun reset() { timestamp = null }

    fun update(x: Float, y: Float, z: Float, timeNanos: Long): PhonePlayerGravityPose? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) { reset(); return null }
        val previous = timestamp
        val weight = if (previous == null || timeNanos <= previous) 1f else {
            val seconds = (timeNanos - previous) / 1_000_000_000f
            seconds / (.15f + seconds)
        }
        gravity[0] += weight * (x - gravity[0])
        gravity[1] += weight * (y - gravity[1])
        gravity[2] += weight * (z - gravity[2])
        timestamp = timeNanos
        return phonePlayerGravityPose(gravity[0], gravity[1], gravity[2])
    }
}
