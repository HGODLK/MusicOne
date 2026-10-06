package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*

/** 首批网络内容与页面挂载各自有入场进度，已显示的内容不因返回而重播。 */
@Composable
internal fun rememberEntityDataEntrance(ready: Boolean): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val progress = remember { Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(ready) { if (ready) progress.animateTo(1f, musicMotion(300)) }
    return progress
}
