package com.musicone

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

// 列表保留原位移与遮罩，只放缓起步和收尾；拖拽松手仍交给连续速度的弹簧。
internal val PlayerQueueEnterAnimation = tween<Float>(360, easing = CubicBezierEasing(.25f, 0f, .15f, 1f))
internal val PlayerQueueExitAnimation = tween<Float>(280, easing = CubicBezierEasing(.25f, 0f, .15f, 1f))

// 与播放页、歌单手势相同，整页位移必须使用细进度阈值，不能提前吸附到终点。
internal val PlayerQueueGestureAnimation = musicSpring<Float>(stiffness = 360f, visibilityThreshold = .0001f)
