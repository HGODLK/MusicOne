package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
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

/** 检查部分露出的锚点是否可用：只要可见区域落在宿主内且有面积即可，完整范围允许超出屏幕。 */
internal fun partialAnchorUsable(visible: Rect, host: Rect): Boolean = anchorFits(visible, host)

internal fun anchorFits(anchor: Rect, host: Rect): Boolean =
    anchor.width > 1f && anchor.height > 1f &&
        anchor.left >= host.left - 1f && anchor.top >= host.top - 1f &&
        anchor.right <= host.right + 1f && anchor.bottom <= host.bottom + 1f

internal fun shouldDismissPlayer(progress: Float, velocityY: Float, releasedNormally: Boolean,
                                 distanceDp: Float = 24f, reverseDistanceDp: Float = 0f): Boolean =
    playerGestureTarget(1f - progress, velocityY, distanceDp, false, releasedNormally,
        threshold = .5f, reverseDistanceDp = reverseDistanceDp,
        flingDistanceDp = 32f, flingVelocityDp = 650f)

internal fun contentReveal(progress: Float): Float = ((progress - .22f) / .78f).coerceIn(0f, 1f)

internal fun playlistDetailProgress(pageProgress: Float, entranceProgress: Float): Float =
    minOf(contentReveal(pageProgress), entranceProgress).coerceIn(0f, 1f)

/** 迷你播放条外轮廓圆角（dp）：经典底栏 19，并排底栏与导航胶囊统一为 20；飞行玻璃各处经 [playerSurfaceCorner] 读取同一个值。 */
internal object MiniPlayerSurfaceCorner {
    var dp by mutableFloatStateOf(19f)

    /** 播放页展开终点的屏幕圆角（dp），由 PlayerScreenCornerEffect 按当前窗口同步；取不到时为 0。 */
    var screenDp by mutableFloatStateOf(0f)
}

/** 迷你播放条浅色玻璃的白度，随底栏布局取值；飞行玻璃的起始白度和迷你条文字取色读同一个值。 */
internal object MiniPlayerSurfaceTint {
    var light by mutableFloatStateOf(.28f)
}

/** 运动途中从迷你条圆角过渡到屏幕圆角；完全展开后回到直角，截图和录屏的四角不会露出下层页面。 */
internal fun playerSurfaceCorner(progress: Float): Float =
    if (progress >= 1f) 0f else motionLerp(MiniPlayerSurfaceCorner.dp, MiniPlayerSurfaceCorner.screenDp, progress)

/** 歌词展开时按缩放比例分段补偿封面圆角，保持视觉上约 12dp，并减少动画中频繁更新裁剪形状。 */
internal fun phoneArtworkCorner(progress: Float): Float {
    val scale = motionLerp(1f, 62f / 360f, progress)
    val compensated = 12f / scale.coerceAtLeast(62f / 360f)
    return (compensated / 4f).roundToInt() * 4f
}

internal fun shouldRefreshPlayerBackdrop(phase: MotionPhase, wantsOpen: Boolean, progress: Float): Boolean =
    phase == MotionPhase.HIDDEN || phase == MotionPhase.PREPARING || (!wantsOpen && progress < .25f)
