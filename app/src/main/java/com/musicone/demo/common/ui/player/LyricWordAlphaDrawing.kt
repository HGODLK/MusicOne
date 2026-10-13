package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * 正文录制完整字形；外伸词段使用独立墨迹，明暗与缩放不参与测量排版。
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
                val isolateInk = lyricWordInkIsolationRequired(layout.layoutInput.text.text)
                // 平板大字号的 j 钩部超过 4dp；只扩展栅格留白，不改变正文宽度。
                val inkOverhang = if (isolateInk) (0 until layout.lineCount).maxOf {
                    (layout.getLineBottom(it) - layout.getLineTop(it)) * .12f + 1f
                } else 0f
                val overhang = ceil(maxOf(4.dp.toPx(), inkOverhang))
                val bitmapWidth = ceil(size.width + overhang * 2).toInt()
                val bitmapHeight = ceil(size.height + overhang * 2).toInt()
                val bitmap = lyricTextRaster(layout, IntRect(-overhang.toInt(), -overhang.toInt(),
                    bitmapWidth - overhang.toInt(), bitmapHeight - overhang.toInt()), color)
                val scene = playbackTrace("MusicOne:lyric-scene-prepare") {
                    LyricWordRenderScene.create(this, layout, ranges, bitmap, overhang, color, isolateInk)
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
