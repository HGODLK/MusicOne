package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*

/** 控制首批网络数据加载完后的淡入动画，已经显示过的内容在返回时不重播。 */
@Composable
internal fun rememberEntityDataEntrance(ready: Boolean): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val progress = remember { Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(ready) { if (ready) progress.animateTo(1f, musicMotion(300)) }
    return progress
}
