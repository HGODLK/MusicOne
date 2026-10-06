package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI

internal const val PLAYER_ATMOSPHERE_CYCLE_SECONDS = 30.0

internal fun nextPlayerAtmospherePhase(current: Double, elapsedNanos: Long): Double =
    current + elapsedNanos / 1_000_000_000.0 / PLAYER_ATMOSPHERE_CYCLE_SECONDS * PI * 2.0

/** 播放器反复展开时复用同一流动相位，转场和卸载期间保持冻结。 */
@Stable
internal class PlayerAtmosphereMotionState {
    private val phaseState = mutableDoubleStateOf(0.0)
    val phase: Double get() = phaseState.doubleValue

    fun advance(elapsedNanos: Long) {
        // 不取模；各色块使用不同非整数频率时，取模会在周期边界制造位置跳变。
        phaseState.doubleValue = nextPlayerAtmospherePhase(phaseState.doubleValue, elapsedNanos)
    }
}

@Composable
internal fun rememberPlayerAtmosphereMotionState() = remember { PlayerAtmosphereMotionState() }

internal val LocalPlayerAtmosphereMotion = staticCompositionLocalOf<PlayerAtmosphereMotionState> {
    error("缺少播放器色场相位状态")
}

internal fun playerAtmosphereCanAdvance(phase: MotionPhase?): Boolean = phase == MotionPhase.SHOWN
