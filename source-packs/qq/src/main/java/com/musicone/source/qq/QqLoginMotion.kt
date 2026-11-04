package com.musicone.source.qq

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/** 包内转场遵循宿主减少动态效果设置，并沿用既有设置页曲线。 */
internal fun <T> qqLoginMotion(reduceMotion: Boolean, durationMillis: Int): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else tween(durationMillis, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
