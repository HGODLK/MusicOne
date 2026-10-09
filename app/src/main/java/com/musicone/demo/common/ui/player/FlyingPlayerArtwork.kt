package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FlyingPlayerArtwork(motion: PageMotion, track: MusicTrack) {
    val frame = rememberPlayerOpeningArtwork(track, motion)
    // 换图按时间推进，手指停在转场中途时仍能完成渐变；几何继续跟随手势。
    ReadyArtworkCrossfade(frame, 420, Modifier.fillMaxSize(), modulateAlpha = true,
        adoptPreparedFrame = motion.wantsOpen &&
            (motion.phase == MotionPhase.PREPARING || motion.phase == MotionPhase.MOVING) &&
            PlayerOpeningArtworkHandoff.frameFor(track) != null) { artwork ->
        FlyingPlayerArtworkFrame(motion, artwork)
    }
}

@Composable
private fun FlyingPlayerArtworkFrame(motion: PageMotion, frame: PlayerArtworkFrame) {
    val bitmap = frame.bitmap
    val identity = frame.identity
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black))
    val text = remember(identity.mark, style, measurer) { measurer.measure(identity.mark, style) }
    val path = remember { Path() }
    // 画布在准备阶段就常驻，和源、目标封面在同一绘制帧交接，避免源封面先隐藏一帧。
    Canvas(Modifier.fillMaxSize()) {
        if (!motion.moving) return@Canvas
        val source = motion.sourceSnapshot["cover"] ?: return@Canvas
        val target = motion.targetSnapshot["cover"] ?: return@Canvas
        val p = motion.value
        val bounds = motionRect(source.bounds, target.bounds, p)
            .translate(-motion.hostBounds.left, -motion.hostBounds.top)
        path.reset()
        path.addRoundRect(RoundRect(bounds, CornerRadius(motionLerp(source.corner, target.corner, p).dp.toPx())))
        clipPath(path) {
            if (image != null) {
                drawCoverBitmap(image, bounds)
                return@clipPath
            }
            drawRect(
                Brush.linearGradient(listOf(Color(identity.start), Color(identity.end)), bounds.topLeft, bounds.bottomRight),
                bounds.topLeft,
                bounds.size,
            )
            val textScale = motionLerp(source.markSize, target.markSize, p) / 120f
            val textWidth = text.size.width * textScale
            val textHeight = text.size.height * textScale
            val biasX = motionLerp(source.markX, target.markX, p)
            val biasY = motionLerp(source.markY, target.markY, p)
            val paddingScale = motionLerp(source.contentScale, target.contentScale, p)
            val horizontalPadding = 11.dp.toPx() * paddingScale
            val verticalPadding = 8.dp.toPx() * paddingScale
            val x = bounds.left + horizontalPadding + (biasX + 1f) * .5f *
                (bounds.width - textWidth - horizontalPadding * 2f)
            val y = bounds.top + verticalPadding + (biasY + 1f) * .5f *
                (bounds.height - textHeight - verticalPadding * 2f)
            scale(textScale, textScale, Offset(x, y)) {
                drawText(text, topLeft = Offset(x, y))
            }
        }
    }
}
