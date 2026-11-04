package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import kotlin.math.ceil

/**
 * 正文录制完整字形；外伸词段使用独立墨迹，明暗不参与测量排版。
 * 跨视觉行的词段保持原排版位置，路径仅随实际文字布局变化重新缓存。
 */
@Composable
internal fun Modifier.lyricWordAlphaDrawing(
    ranges: List<LyricHighlightRange>,
    textLayout: () -> TextLayoutResult?,
    motion: LyricWordLightMotion,
    color: Color,
    prepareAhead: Boolean = false,
    prepareForTurn: Boolean = false,
): Modifier {
    if (ranges.isEmpty()) return this

    val measured = textLayout()
    val preparation = remember(measured, ranges) { measured?.let { LyricWordPreparation(it, ranges) } }
    val preparingAhead = rememberUpdatedState(prepareAhead)
    val preparingTurn = rememberUpdatedState(prepareForTurn)
    LaunchedEffect(preparation, color, prepareAhead) {
        if (prepareAhead) preparation?.prepare(color)
    }
    val drawing = remember(ranges, textLayout, motion, color, preparation) {
        Modifier.lyricDrawWithCache {
            val layout = textLayout()
            if (layout == null || size.width <= 0f || size.height <= 0f) {
                onDrawWithContent { drawContent() }
            } else {
                val isolateInk = lyricWordInkIsolationRequired(layout.layoutInput.text.text)
                // 平板大字号的 j 钩部超过 4dp；只扩展栅格留白，不改变正文宽度。
                val overhang = lyricRasterOverhang(layout)
                val bitmapWidth = ceil(size.width + overhang * 2).toInt()
                val bitmapHeight = ceil(size.height + overhang * 2).toInt()
                val bitmap = lyricTextRaster(layout, IntRect(-overhang.toInt(), -overhang.toInt(),
                    bitmapWidth - overhang.toInt(), bitmapHeight - overhang.toInt()), color)
                val scene = playbackTrace("MusicOne:lyric-scene-prepare") {
                    LyricWordRenderScene.create(this, layout, ranges, bitmap, overhang, color, isolateInk,
                        preparation?.takeIf { it.layout == layout })
                }
                onDrawWithContent {
                    if (scene == null) {
                        drawContent()
                    } else {
                        // 转向关键句在旧画面覆盖时完成准备，正式旋转不再临时建逐字图层。
                        if (preparingTurn.value || preparingAhead.value && preparation?.ready == true) scene.prepareAhead()
                        drawLayer(scene.layerFor(motion))
                    }
                }
            }
        }
    }
    return this.then(drawing)
}
