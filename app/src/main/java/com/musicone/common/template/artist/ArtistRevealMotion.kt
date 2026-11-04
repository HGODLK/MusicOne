package com.musicone

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/** 背景大图与主题取色共用时长、曲线及减少动态效果语义。 */
internal fun artistArtworkRevealMotion(): FiniteAnimationSpec<Float> =
    if (ExperiencePreferences.options.reduceMotion) snap()
    else tween(560, easing = FastOutSlowInEasing)
