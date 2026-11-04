package com.musicone

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

internal data class PlayerSurfaceFlightFrame(
    val targetBounds: Rect,
    val geometry: PhonePlayerFlightGeometry,
    val sourceGeometry: PhonePlayerFlightGeometry,
    val contentGeometry: PlayerFlightContentFrame? = null,
)

/** 同一组可观察输入只求解一次；各图层仍在绘制阶段读取，不把动画进度带入重组。 */
internal fun playerSurfaceFlightState(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int)
    : State<PlayerSurfaceFlightFrame?> = derivedStateOf {
    val source = playerSourceAnchor(motion, "surface") ?: return@derivedStateOf null
    val target = motion.targetSnapshot["surface"] ?: motion.targets["surface"] ?: return@derivedStateOf null
    val geometry = phonePlayerFlightGeometry(motion, flight, rotation, "surface", source.bounds, target.bounds)
    val sourceGeometry = phonePlayerFlightSource(motion, flight, rotation, "surface", source.bounds)
    PlayerSurfaceFlightFrame(target.bounds, geometry, sourceGeometry,
        playerFlightContentFrame(motion, flight, rotation, source.bounds, sourceGeometry, geometry))
}

internal val LocalPlayerSurfaceFlightFrame = staticCompositionLocalOf<State<PlayerSurfaceFlightFrame?>?> { null }

/** 源、目标文字分别绘制，但共用同一条飞行轨迹，切歌的实测文字端点仍实时参与。 */
internal fun playerControlFlightState(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int,
    key: String, geometry: PlayerTextGeometry? = null,
    surfaceFrame: State<PlayerSurfaceFlightFrame?>? = null): State<PhonePlayerFlightGeometry?> = derivedStateOf {
    if (!motion.moving) return@derivedStateOf null
    val capturedSource = motion.sourceSnapshot[key] ?: return@derivedStateOf null
    val source = playerSourceAnchor(motion, key, geometry?.sourceFor(capturedSource) ?: capturedSource)
        ?: return@derivedStateOf null
    val capturedTarget = motion.targetSnapshot[key] ?: return@derivedStateOf null
    val target = geometry?.targetFor(capturedTarget) ?: capturedTarget
    phonePlayerFlightGeometry(motion, flight, rotation, key, source.bounds, target.bounds, surfaceFrame?.value)
}
