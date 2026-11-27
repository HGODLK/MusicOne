package com.musicone.source.qq

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/** 登录页切换动画：跟随宿主“减少动态效果”开关，曲线和设置页一致。 */
internal fun <T> qqLoginMotion(reduceMotion: Boolean, durationMillis: Int): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else tween(durationMillis, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
