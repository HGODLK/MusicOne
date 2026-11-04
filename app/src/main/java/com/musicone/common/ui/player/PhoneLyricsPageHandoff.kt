package com.musicone

import androidx.compose.ui.geometry.Offset

/** 歌词沿用整页飞行中心的真实行程，字形和固定阅读视口不随迷你控件缩放。 */
internal fun phoneLyricsPageOffset(motion: PageMotion, flight: PhonePlayerFlightRotation?, rotation: Int): Offset {
    if (!motion.moving) return Offset.Zero
    val source = playerSourceAnchor(motion, "surface")?.bounds ?: return Offset.Zero
    val target = motion.targetSnapshot["surface"]?.bounds ?: return Offset.Zero
    val current = phonePlayerFlightGeometry(motion, flight, rotation, "surface", source, target)
    return current.bounds.center - target.center
}

internal fun phoneLyricsPageHeld(phase: MotionPhase, progress: Float): Boolean =
    phase == MotionPhase.DRAGGING && progress > 0f && progress < 1f
