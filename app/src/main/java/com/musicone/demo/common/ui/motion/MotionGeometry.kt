package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun motionDuration(from: Float, to: Float, fullDuration: Int): Int =
    (abs(to - from).coerceIn(0f, 1f) * fullDuration).roundToInt().coerceAtLeast(1)

internal fun motionLerp(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress.coerceIn(0f, 1f)

internal fun motionRect(start: Rect, end: Rect, progress: Float): Rect = Rect(
    motionLerp(start.left, end.left, progress), motionLerp(start.top, end.top, progress),
    motionLerp(start.right, end.right, progress), motionLerp(start.bottom, end.bottom, progress),
)

internal fun anchorFits(anchor: Rect, host: Rect): Boolean =
    anchor.width > 1f && anchor.height > 1f &&
        anchor.left >= host.left - 1f && anchor.top >= host.top - 1f &&
        anchor.right <= host.right + 1f && anchor.bottom <= host.bottom + 1f

internal fun shouldDismissPlayer(progress: Float, velocityY: Float, releasedNormally: Boolean): Boolean =
    releasedNormally && (progress <= .65f || velocityY >= 900f && progress < .94f)

internal fun contentReveal(progress: Float): Float = ((progress - .22f) / .78f).coerceIn(0f, 1f)

internal fun playlistDetailProgress(pageProgress: Float, entranceProgress: Float): Float =
    minOf(contentReveal(pageProgress), entranceProgress).coerceIn(0f, 1f)

internal fun playerSurfaceCorner(progress: Float): Float = motionLerp(19f, 0f, progress)

/** 歌词紧凑态用量化补偿维持约 12dp 视觉圆角，减少动画中的形状更新次数。 */
internal fun phoneArtworkCorner(progress: Float): Float {
    val scale = motionLerp(1f, 62f / 360f, progress)
    val compensated = 12f / scale.coerceAtLeast(62f / 360f)
    return (compensated / 4f).roundToInt() * 4f
}

internal fun shouldRefreshPlayerBackdrop(phase: MotionPhase, wantsOpen: Boolean, progress: Float): Boolean =
    phase == MotionPhase.HIDDEN || phase == MotionPhase.PREPARING || (!wantsOpen && progress < .25f)
