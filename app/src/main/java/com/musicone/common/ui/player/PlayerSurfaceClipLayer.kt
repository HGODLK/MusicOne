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

/** 用双层 GraphicsLayer 实现旋转圆角裁剪：在属性阶段更新裁剪轮廓与正反旋转，内容仍实时渲染不冻结像素。 */
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
            // 直接改子 GraphicsLayer 属性不会自动触发宿主重绘，手动调用 invalidate 提交这一帧。
            view.invalidate()
        }
        // 外层节点本身不做变换，只驱动内部图层，避免影响子树的布局坐标系。
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
            // 录制对子图层的引用而非静态位图，切歌、歌词和时钟仍会实时更新。
            fixedContent.record { this@drawWithContent.drawContent() }
            clippedContent.record { drawLayer(fixedContent) }
            clippedContent.clip = true
            capture.ready = true
            capture.size = measured
            capture.generation = generation
        }
        if (preparing) {
            // 准备阶段先录制好图层引用并上报就绪，此时外层透明度为 0，不会提前露出画面。
            preparation?.record(preparationLayer, measured)
            drawLayer(fixedContent)
            return@drawWithContent
        }
        drawLayer(clippedContent)
    }
}
