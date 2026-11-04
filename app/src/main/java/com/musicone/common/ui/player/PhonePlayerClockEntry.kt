package com.musicone

import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.withTimeoutOrNull

/** 隐藏请求不依赖转向准备和代次，防止退场被连续取消或被渐变降级跳过。 */
internal data class PhoneClockTransitionRequest(val target: Float, val rotation: Int = 0,
    val revealWithPage: Boolean = false, val preparing: Boolean = false,
    val fading: Boolean = false, val generation: Int = 0)

internal fun phoneClockTransitionRequest(target: Float, rotation: Int, revealWithPage: Boolean,
    preparing: Boolean, fading: Boolean, generation: Int): PhoneClockTransitionRequest =
    if (target == 0f) PhoneClockTransitionRequest(0f)
    else PhoneClockTransitionRequest(target, rotation, revealWithPage, preparing, fading, generation)

/** 端点交接失败后继续等待本轮新登记，不因旧代次返回而永久放弃入口。 */
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

/** 已经飞行的时钟保留位置和速度，页面状态改变不能再把它吸到终点。 */
internal fun phoneClockRevealWithPage(revealWithPage: Boolean, progress: Float, velocity: Float): Boolean =
    revealWithPage && progress == 0f && velocity == 0f

/** 速度独立保存，取消旧任务不会丢失飞入过程的动量，反向依靠同一弹簧连续加速。 */
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
