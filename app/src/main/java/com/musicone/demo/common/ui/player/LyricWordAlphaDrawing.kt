package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private data class LyricTextRasterKey(
    val layoutInput: TextLayoutInput,
    val width: Int,
    val height: Int,
    val overhang: Int,
    val color: Color,
)

private class LyricTextRasterCache {
    private val entries = LinkedHashMap<LyricTextRasterKey, ImageBitmap>(8, .75f, true)
    private var retainedBytes = 0L

    @Synchronized
    fun getOrCreate(key: LyricTextRasterKey, draw: (ImageBitmap) -> Unit): ImageBitmap {
        entries[key]?.let { return it }

        val bitmap = ImageBitmap(key.width, key.height)
        playbackTrace("MusicOne:lyric-raster-miss") { draw(bitmap) }
        val bytes = key.width.toLong() * key.height * 4L
        if (bytes <= MAX_RETAINED_BYTES) {
            entries[key] = bitmap
            retainedBytes += bytes
            val iterator = entries.entries.iterator()
            while (retainedBytes > MAX_RETAINED_BYTES && iterator.hasNext()) {
                val eldest = iterator.next()
                retainedBytes -= eldest.key.width.toLong() * eldest.key.height * 4L
                iterator.remove()
            }
        }
        return bitmap
    }

    private companion object {
        const val MAX_RETAINED_BYTES = 8L * 1024L * 1024L
    }
}

private val lyricTextRasterCache = LyricTextRasterCache()

/**
 * 正文只录制一次完整字形；词段重放同一纹理，明暗与缩放不参与测量排版。
 * 跨视觉行的词段分别围绕原基线缩放，路径仅随实际文字布局变化重新缓存。
 */
@Composable
internal fun Modifier.lyricWordAlphaDrawing(
    ranges: List<LyricHighlightRange>,
    textLayout: () -> TextLayoutResult?,
    motion: LyricWordLightMotion,
    color: Color,
): Modifier {
    if (ranges.isEmpty()) return this

    val drawing = remember(ranges, textLayout, motion, color) {
        Modifier.drawWithCache {
            val layout = textLayout()
            if (layout == null || size.width <= 0f || size.height <= 0f) {
                onDrawWithContent { drawContent() }
            } else {
                val overhang = ceil(4.dp.toPx())
                val bitmapWidth = ceil(size.width + overhang * 2).toInt()
                val bitmapHeight = ceil(size.height + overhang * 2).toInt()
                val rasterKey = LyricTextRasterKey(layout.layoutInput, bitmapWidth, bitmapHeight, overhang.toInt(), color)
                val bitmap = lyricTextRasterCache.getOrCreate(rasterKey) { raster ->
                    CanvasDrawScope().draw(this, layoutDirection, Canvas(raster), Size(raster.width.toFloat(), raster.height.toFloat())) {
                        translate(overhang, overhang) { drawText(layout, color = color) }
                    }
                }
                val scene = playbackTrace("MusicOne:lyric-scene-prepare") {
                    LyricWordRenderScene.create(this, layout, ranges, bitmap, overhang)
                }
                onDrawWithContent {
                    if (scene == null) {
                        drawContent()
                    } else {
                        drawLayer(scene.layerFor(motion))
                    }
                }
            }
        }
    }
    return this.then(drawing)
}
