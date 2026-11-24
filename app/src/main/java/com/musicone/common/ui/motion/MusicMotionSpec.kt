package com.musicone

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing

/** 常规转场按真实毫秒完成；手势归位仍使用独立物理弹簧。delayMillis 用于依次出现的同类元素。 */
internal fun <T> musicMotion(responseMillis: Int = 320, delayMillis: Int = 0): FiniteAnimationSpec<T> {
    if (responseMillis <= 0 || ExperiencePreferences.options.reduceMotion) return snap()
    return tween(responseMillis, delayMillis.coerceAtLeast(0), CubicBezierEasing(.2f, 0f, 0f, 1f))
}

internal fun <T> musicSpring(stiffness: Float = 240f, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    if (ExperiencePreferences.options.reduceMotion) snap() else
        spring(dampingRatio = 1f, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
