package com.musicone

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/** 背景大图淡入和主题色过渡共用同一套动画曲线，开启减少动态效果时直接跳变。 */
internal fun artistArtworkRevealMotion(): FiniteAnimationSpec<Float> =
    if (ExperiencePreferences.options.reduceMotion) snap()
    else tween(560, easing = FastOutSlowInEasing)
