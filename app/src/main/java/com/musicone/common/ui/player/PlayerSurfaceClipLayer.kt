package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private class PlayerSurfaceClipCapture {
    var ready = false
    var size = IntSize.Zero
    var generation = -1
}

/** 属性阶段更新原生轮廓与正反旋转，绘制阶段只保留稳定的实时子图层引用。 */
@Composable
internal fun Modifier.playerSurfaceClipLayer(motion: PageMotion,
    geometry: State<PhonePlayerFlightGeometry>, preparationLayer: PlayerOpeningLayer): Modifier {
    val fixedContent = rememberGraphicsLayer()
    val clippedContent = rememberGraphicsLayer()
    val capture = remember { PlayerSurfaceClipCapture() }
    val preparation = LocalPlayerOpeningLayers.current
    val view = LocalView.current
    return graphicsLayer {
        if (motion.phase == MotionPhase.PREPARING) {
            fixedContent.rotationZ = 0f
        } else if (motion.moving) {
            val pose = geometry.value
            val corner = playerSurfaceCorner(motion.value).dp.toPx()
            clippedContent.setRoundRectOutline(pose.bounds.topLeft, pose.bounds.size, corner)
            clippedContent.pivotOffset = pose.bounds.center
            clippedContent.rotationZ = pose.rotation
            fixedContent.pivotOffset = pose.bounds.center
            fixedContent.rotationZ = -pose.rotation
            // 原生属性自身不通知宿主；沿用已有帧驱动的提交方式，不重录本层指令。
            view.invalidate()
        }
        // 此节点自身不做变换；逐帧只更新下方原生图层，不改变子树的布局坐标链。
    }.drawWithContent {
        val preparing = motion.phase == MotionPhase.PREPARING
        if (!motion.moving && !preparing) {
            capture.ready = false
            drawContent()
            return@drawWithContent
        }
        val measured = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val generation = preparation?.generation ?: 0
        if (!capture.ready || capture.size != measured || capture.generation != generation) {
            // 只引用下一层稳定的 graphicsLayer；切歌、歌词、时钟继续更新原节点，不冻结像素。
            fixedContent.record { this@drawWithContent.drawContent() }
            clippedContent.record { drawLayer(fixedContent) }
            clippedContent.clip = true
            capture.ready = true
            capture.size = measured
            capture.generation = generation
        }
        if (preparing) {
            // 准备帧只建立原引用链，正文仍受外层零透明度控制；不能提前显露飞行内容。
            preparation?.record(preparationLayer, measured)
            drawLayer(fixedContent)
            return@drawWithContent
        }
        drawLayer(clippedContent)
    }
}
