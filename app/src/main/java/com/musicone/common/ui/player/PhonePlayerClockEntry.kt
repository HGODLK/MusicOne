package com.musicone

import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.withTimeoutOrNull

/** 构造时钟过渡请求：隐藏时忽略转屏准备状态和版本号，避免退场动画被反复取消。 */
internal data class PhoneClockTransitionRequest(val target: Float, val rotation: Int = 0,
    val revealWithPage: Boolean = false, val preparing: Boolean = false,
    val fading: Boolean = false, val generation: Int = 0)

internal fun phoneClockTransitionRequest(target: Float, rotation: Int, revealWithPage: Boolean,
    preparing: Boolean, fading: Boolean, generation: Int): PhoneClockTransitionRequest =
    if (target == 0f) PhoneClockTransitionRequest(0f)
    else PhoneClockTransitionRequest(target, rotation, revealWithPage, preparing, fading, generation)

/** 在预算时间内等待横屏时钟的目标布局端点就绪。 */
internal suspend fun awaitPhoneClockEndpoint(turn: PhonePlayerTurn?, rotation: Int, budgetMillis: Long): Boolean {
    if (turn == null || turn.endpointReady("clock", rotation) && !turn.clockHandoffStalled(rotation)) return true
    if (budgetMillis <= 0L) return false
    return withTimeoutOrNull(budgetMillis) {
        while (true) {
            turn.refreshEndpoints(setOf("clock"), rotation)
            if (turn.endpointReady("clock", rotation) && !turn.clockHandoffStalled(rotation)) break
            withFrameNanos { }
        }
        true
    } == true
}

/** 只有时钟还没起步且处于静止状态时，才随整页直接显现；已经在飞的保持原动画。 */
internal fun phoneClockRevealWithPage(revealWithPage: Boolean, progress: Float, velocity: Float): Boolean =
    revealWithPage && progress == 0f && velocity == 0f

/** 横屏时钟的进出弹簧控制器：单独保存实时速度，中途取消或反向时动量不丢失。 */
internal class PhoneClockFlight {
    val progress = Animatable(0f)
    var velocity = 0f
        private set
    private var waitingGeneration = -1
    private var waitingDeadline = 0L

    fun endpointBudget(generation: Int, sharedBudget: Long, nowNanos: Long = System.nanoTime()): Long {
        if (waitingGeneration != generation) {
            waitingGeneration = generation
            waitingDeadline = nowNanos + sharedBudget * 1_000_000L
        }
        return minOf(sharedBudget, ((waitingDeadline - nowNanos) / 1_000_000L).coerceAtLeast(0L))
    }

    fun clearEndpointWait() { waitingGeneration = -1 }

    suspend fun snapTo(target: Float) { progress.snapTo(target); velocity = 0f }

    suspend fun animateTo(target: Float, travel: Float) {
        if (ExperiencePreferences.options.reduceMotion) { snapTo(target); return }
        progress.animateTo(target, musicSpring(stiffness = 210f,
            visibilityThreshold = .05f / travel.coerceAtLeast(1f)), initialVelocity = velocity) {
            this@PhoneClockFlight.velocity = this.velocity
        }
        velocity = 0f
    }
}
