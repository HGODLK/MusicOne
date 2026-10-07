package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** 返回播放页关联菜单时复用实时表面，避免旧像素快照重新带回上一曲的模糊。 */
@Composable
internal fun EntityReturnMenuSurface(layer: GraphicsLayer) {
    Canvas(Modifier.fillMaxSize()) {
        if (layer.size.width > 0 && layer.size.height > 0) {
            scale(size.width / layer.size.width, size.height / layer.size.height, Offset.Zero) {
                drawLayer(layer)
            }
        }
    }
}
