package com.musicone

internal data class LyricBoundaryEdges(val top: Float, val bottom: Float)

/** 窗口行程包含测量阶段写入的普通字段，必须在实际绘制时重新读取。 */
internal class LyricBoundaryViewport(private val window: LyricBoundaryWindow, private val height: () -> Float) {
    fun read(): LyricBoundaryEdges {
        val shift = window.offset()
        val viewportHeight = height()
        window.edges?.let { return it(viewportHeight, shift) }
        return LyricBoundaryEdges((window.topBuffer - shift).coerceAtLeast(0f),
            (viewportHeight - shift).coerceAtMost(viewportHeight))
    }

}

/** 完全清晰的段落复用透明度表；边缘仍逐排版行执行原公式。 */
internal class LyricBoundaryAlphaSampler(private val bands: List<Pair<Float, Float>>) {
    private val clear = List(bands.size) { 1f }
    private val firstTop = bands.minOfOrNull { it.first } ?: 0f
    private val lastTop = bands.maxOfOrNull { it.first } ?: 0f
    private val firstBottom = bands.minOfOrNull { it.second } ?: 0f
    private val lastBottom = bands.maxOfOrNull { it.second } ?: 0f

    fun sample(origin: Float, scale: Float, edges: LyricBoundaryEdges, fade: Float,
        partialTop: Boolean = false): List<Float> {
        if (bands.isEmpty()) return clear
        if (bands.size == 1) {
            val alpha = lyricBoundaryLineAlpha(origin + firstTop * scale, origin + firstBottom * scale,
                edges.top, edges.bottom, fade, partialTop)
            return if (alpha == 1f) clear else listOf(alpha)
        }
        if (scale >= 0f &&
            lyricBoundaryLineAlpha(origin + firstTop * scale, origin + firstBottom * scale,
                edges.top, edges.bottom, fade, partialTop) == 1f &&
            lyricBoundaryLineAlpha(origin + lastTop * scale, origin + lastBottom * scale,
                edges.top, edges.bottom, fade, partialTop) == 1f) return clear
        return bands.map { (top, bottom) ->
            lyricBoundaryLineAlpha(origin + top * scale, origin + bottom * scale,
                edges.top, edges.bottom, fade, partialTop)
        }
    }
}
