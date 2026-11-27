package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 用与毛玻璃相同的旋转圆角轮廓裁剪飞行中的封面和文字，防止内容越出玻璃边界。 */
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
