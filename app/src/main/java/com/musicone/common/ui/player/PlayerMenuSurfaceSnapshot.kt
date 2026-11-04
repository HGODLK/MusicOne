package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize

/** 详情页使用物理方向的菜单表面；菜单本身仍在原横向画布绘制。 */
internal fun Modifier.playerMenuSurfaceSnapshot(surface: GraphicsLayer, rotation: Int): Modifier = composed {
    if (rotation == 0) drawWithContent {
        surface.record { this@drawWithContent.drawContent() }
        drawLayer(surface)
    } else {
        val localSurface = rememberGraphicsLayer()
        drawWithContent {
            localSurface.record { this@drawWithContent.drawContent() }
            surface.record(size = IntSize(localSurface.size.height, localSurface.size.width)) {
                translate(if (rotation > 0) size.width else 0f, if (rotation < 0) size.height else 0f) {
                    rotate(rotation.toFloat(), Offset.Zero) { drawLayer(localSurface) }
                }
            }
            drawLayer(localSurface)
        }
    }
}
