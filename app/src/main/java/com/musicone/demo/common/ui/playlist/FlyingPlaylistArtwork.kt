package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@Composable
internal fun FlyingPlaylistArtwork(motion: PageMotion, imageUrl: String?, start: Long, end: Long, mark: String,
    followPull: Boolean = true, centeredMark: Boolean = false, artistAvatar: ArtistAvatarPresentation? = null) {
    val loadedBitmap by rememberArtworkBitmap(imageUrl.takeIf { artistAvatar == null })
    val bitmap = artistAvatar?.bitmap ?: LocalPlaylistCardTransition.current?.activeArtwork ?: loadedBitmap
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black,
        lineHeight = if (centeredMark) 120.sp else androidx.compose.ui.unit.TextUnit.Unspecified))
    val text = remember(mark, style, measurer) { measurer.measure(mark, style) }
    val held = LocalPlaylistCardTransition.current?.activeArtworkLayers
    val heldImages = remember(held) { held?.map { it.frame.bitmap?.asImageBitmap() } }
    val heldText = remember(held, style, measurer) { held?.map { measurer.measure(it.frame.identity.mark, style) } }
    val pageColor = PlaylistPageColor
    val path = remember { Path() }
    val pull = LocalPlaylistPull.current.takeIf { followPull }
    // 画布始终挂载，和源、目标封面在同一绘制帧切换，避免阶段变化时短暂露出页面底色。
    Canvas(Modifier.fillMaxSize()) {
        if (!motion.moving) return@Canvas
        val source = motion.sourceSnapshot[motion.coverKey] ?: return@Canvas
        val target = motion.targetSnapshot[motion.coverKey] ?: return@Canvas
        val p = motion.value
        val bounds = (pull?.artworkBounds(motion, source.bounds, target.bounds) ?: motionRect(source.bounds, target.bounds, p))
            .translate(-motion.hostBounds.left, -motion.hostBounds.top)
        path.reset()
        path.addRoundRect(RoundRect(bounds, CornerRadius(motionLerp(source.corner, target.corner, p).dp.toPx())))
        clipPath(path) {
            if (!held.isNullOrEmpty() && artistAvatar == null) {
                var cumulative = 0f
                held.forEachIndexed { index, layer ->
                    cumulative += layer.opacity
                    val alpha = if (index == 0) 1f else if (cumulative > 0f) layer.opacity / cumulative else 0f
                    val heldImage = heldImages?.get(index)
                    if (heldImage != null) drawCoverBitmap(heldImage, bounds, alpha)
                    else {
                        val identity = layer.frame.identity
                        drawRect(Brush.linearGradient(listOf(Color(identity.start), Color(identity.end)), bounds.topLeft, bounds.bottomRight),
                            bounds.topLeft, bounds.size, alpha = alpha)
                        val factor = motionLerp(source.markSize, target.markSize, p) / 120f
                        val origin = if (centeredMark) bounds.center else Offset(bounds.center.x + 11.dp.toPx() / 2, bounds.center.y - 8.dp.toPx() / 2)
                        val layout = heldText!!.get(index)
                        scale(factor, factor, origin) {
                            drawText(layout, topLeft = Offset(origin.x - layout.size.width / 2f, origin.y - layout.size.height / 2f), alpha = alpha)
                        }
                    }
                }
                drawPlaylistArtworkFade(bounds, motionLerp(source.bottomFade, target.bottomFade, p), pageColor)
                return@clipPath
            }
            val placeholderAlpha = artistAvatar?.placeholderAlpha?.value ?: if (image == null) 1f else 0f
            if (placeholderAlpha > 0f) {
                drawRect(Brush.linearGradient(listOf(Color(start), Color(end)), bounds.topLeft, bounds.bottomRight),
                    bounds.topLeft, bounds.size, alpha = placeholderAlpha)
                val factor = motionLerp(source.markSize, target.markSize, p) / 120f
                val origin = if (centeredMark) bounds.center else
                    Offset(bounds.center.x + 11.dp.toPx() / 2, bounds.center.y - 8.dp.toPx() / 2)
                scale(factor, factor, origin) {
                    drawText(text, topLeft = Offset(origin.x - text.size.width / 2f, origin.y - text.size.height / 2f),
                        alpha = placeholderAlpha)
                }
            }
            if (image != null) drawCoverBitmap(image, bounds, artistAvatar?.imageAlpha?.value ?: 1f)
            val fade = motionLerp(source.bottomFade, target.bottomFade, p)
            drawPlaylistArtworkFade(bounds, fade, pageColor)
        }
    }
}

/** 飞行层与落地封面共用渐白边缘，末端提前一个采样像素覆盖底色。 */
internal fun DrawScope.drawPlaylistArtworkFade(bounds: Rect, fade: Float, pageColor: Color) {
    if (fade <= 0f) return
    val endY = bounds.bottom - 1.dp.toPx()
    val startY = minOf(bounds.bottom - bounds.height * .35f * fade, endY - 1f)
    drawRect(Brush.verticalGradient(
        listOf(pageColor.copy(alpha = 0f), pageColor), startY, endY), bounds.topLeft, bounds.size)
}

internal fun playlistArtworkBleedBounds(bounds: androidx.compose.ui.geometry.Rect, bleed: Float) =
    androidx.compose.ui.geometry.Rect(
        left = bounds.left - bleed,
        top = bounds.top - bleed,
        right = bounds.right + bleed,
        bottom = bounds.bottom + bleed,
    )
