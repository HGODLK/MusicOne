package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 飞行内容使用玻璃同一帧的原生轮廓，旋转及归位中都不能越过承载背景。 */
@Composable
internal fun PlayerFlyingContentHost(motion: PageMotion, content: @Composable () -> Unit) {
    val frame = LocalPlayerSurfaceFlightFrame.current
    val geometry = remember(motion, frame) { derivedStateOf {
        val pose = frame?.value?.geometry
        pose?.copy(bounds = pose.bounds.translate(-motion.hostBounds.left, -motion.hostBounds.top))
            ?: PhonePlayerFlightGeometry(androidx.compose.ui.geometry.Rect.Zero, 0f)
    } }
    Box(Modifier.fillMaxSize().playerSurfaceClipLayer(motion, geometry, PlayerOpeningLayer.FLYING)) {
        // 沿用正文的稳定节点引用方式，子层保持实时绘制，不录制静态像素。
        Box(Modifier.fillMaxSize().graphicsLayer()) { content() }
    }
}
