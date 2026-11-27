package com.musicone

import androidx.compose.ui.geometry.Offset

/** 整页飞行时让歌词跟着页面中心移动，但保持字形和阅读视口原尺寸，不随迷你条一起缩放。 */
internal fun phoneLyricsPageOffset(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int): Offset {
    if (!motion.moving) return Offset.Zero
    val source = playerSourceAnchor(motion, "surface")?.bounds ?: return Offset.Zero
    val target = motion.targetSnapshot["surface"]?.bounds ?: return Offset.Zero
    val current = phonePlayerFlightGeometry(motion, flight, rotation, "surface", source, target)
    return current.bounds.center - target.center
}

internal fun phoneLyricsPageHeld(phase: MotionPhase, progress: Float): Boolean =
    phase == MotionPhase.DRAGGING && progress > 0f && progress < 1f
