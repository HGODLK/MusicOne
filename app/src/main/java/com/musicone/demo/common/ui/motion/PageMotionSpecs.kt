package com.musicone.demo
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** 页面转场指定真实时长，展开舒展、返回利落。 */
internal val PlaylistEnterAnimation = tween<Float>(420, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
internal val PlaylistReturnAnimation = tween<Float>(320, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
internal val PlaylistGestureSettlementAnimation = spring<Float>(
    dampingRatio = 1f, stiffness = 420f, visibilityThreshold = 0.0001f,
)

// 播放页距离较长，保留稍长的展开时间。
internal val PlayerEnterAnimation = tween<Float>(480, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
internal val PlayerReturnAnimation = tween<Float>(360, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))

/** 页面进度映射到整屏距离，手势收尾使用更细精度，避免末段突然吸附。 */
internal val PlayerGestureSettlementAnimation = spring<Float>(
    dampingRatio = 1f,
    stiffness = 320f,
    visibilityThreshold = 0.0001f,
)
