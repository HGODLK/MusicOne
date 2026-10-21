package com.musicone.demo

import java.util.regex.Pattern

internal data class HyperOsIslandSummary(val title: String, val artist: String, val lyric: String)

/** 这是内容预算，不是系统岛的实测尺寸；两侧状态区、封面及模板留白不能分配给文字。 */
internal class HyperOsIslandTextBudget(screenWidthDp: Int, screenHeightDp: Int) {
    private val shortSide = listOf(screenWidthDp, screenHeightDp).filter { it > 0 }.minOrNull() ?: 320
    private val textWidthDp = (shortSide - 2 * 80f - 64f).coerceAtLeast(0f)
    val metadataWidthDp = (textWidthDp / 2f).coerceAtMost(56f)
    // 仅把已有留白之外的余量交给歌词，横屏和平板仍保持统一上限。
    val lyricWidthDp = (textWidthDp - metadataWidthDp).coerceAtMost(80f)

    fun summary(title: String, artist: String, lyric: String, density: Float,
        measure: (String) -> Float): HyperOsIslandSummary {
        val width = metadataWidthDp * density
        val name = fit(title, width, measure)
        // content 是后置小字而非第二行，歌名与歌手必须合用左侧预算。
        val remaining = width - measure(name) - measure(" ")
        val detail = if (name == singleLine(title) && remaining > 0f) fit(artist, remaining, measure) else ""
        return HyperOsIslandSummary(name, detail, fit(lyric, lyricWidthDp * density, measure))
    }

    companion object {
        private val grapheme = Pattern.compile("\\X")
        private val whitespace = Regex("\\s+")
        fun singleLine(text: String): String = text.replace(whitespace, " ").trim()

        /** 以完整字素省略，不能拆开组合音标或连写表情，也不单独显示省略号。 */
        fun fit(text: String, width: Float, measure: (String) -> Float): String {
            val value = singleLine(text)
            if (measure(value) <= width) return value
            val matcher = grapheme.matcher(value)
            var end = 0
            var first = 0
            while (matcher.find()) {
                if (first == 0) first = matcher.end()
                if (measure(value.substring(0, matcher.end()) + "…") > width) break
                end = matcher.end()
            }
            if (end > 0) return value.substring(0, end).trimEnd() + "…"
            // 大字体仍能容纳一个字素时保留它，不为省略号丢掉唯一可读文字。
            return value.substring(0, first).takeIf { measure(it) <= width }.orEmpty()
        }
    }
}
