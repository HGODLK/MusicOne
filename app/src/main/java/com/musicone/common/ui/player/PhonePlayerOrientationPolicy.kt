package com.musicone

/** 方向判定保持为纯策略，系统监听与界面订阅只负责传入当前条件。 */
internal object PhonePlayerOrientationPolicy {
    fun isPhone(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp in 1 until 600

    /** 仅约束内置手机页，传感器原方向继续供音源自定义播放页使用。 */
    fun playerRotation(rotation: Int, disableLandscape: Boolean): Int = if (disableLandscape) 0 else rotation

    fun shouldListen(
        active: Boolean,
        systemAllowsRotation: Boolean,
        ignoreRotationLock: Boolean,
        canDetectOrientation: Boolean,
    ): Boolean = active && canDetectOrientation && (ignoreRotationLock || systemAllowsRotation)

    /** 保留音源自定义页原有方向记忆；内置页另用重力姿态及严格稳定窗口。 */
    fun rotationDegrees(orientation: Int, previous: Int): Int = when {
        orientation == -1 -> previous
        previous == -90 && orientation in 40..140 -> -90
        previous == 90 && orientation in 220..320 -> 90
        orientation in 60..120 -> -90
        orientation in 240..300 -> 90
        orientation in 0..25 || orientation in 335..359 || orientation in 155..205 -> 0
        else -> previous
    }
}

/** 稳定窗口独立于传感器回调；截止时即使设备已静止，也能提交最后一次候选方向。 */
internal class PhonePlayerOrientationStability(initialRotation: Int = 0) {
    var rotationDegrees = initialRotation
        private set
    private var candidate: Int? = null
    var decisionAtMs: Long? = null
        private set

    fun reset(rotation: Int) {
        rotationDegrees = rotation
        candidate = null
        decisionAtMs = null
    }

    fun update(orientation: Int, nowMs: Long): Int {
        val target = PhonePlayerOrientationPolicy.rotationDegrees(orientation, rotationDegrees)
        if (orientation == -1 || target == rotationDegrees) {
            candidate = null
            decisionAtMs = null
        } else if (candidate != target) {
            candidate = target
            decisionAtMs = nowMs + if (target == 0) 320L else 80L
        }
        return settle(nowMs)
    }

    fun settle(nowMs: Long): Int {
        val deadline = decisionAtMs
        if (deadline != null && nowMs >= deadline) reset(requireNotNull(candidate))
        return rotationDegrees
    }
}
