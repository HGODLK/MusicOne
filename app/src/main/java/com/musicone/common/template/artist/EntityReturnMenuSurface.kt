package com.musicone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** 回到播放页的关联菜单时直接画实时画面，避免用旧截图把上一首歌的模糊背景带回来。 */
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
