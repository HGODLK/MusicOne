package com.musicone

import java.util.regex.Pattern
import kotlin.math.roundToInt

internal data class SystemLyricInterval(val text: String, val startMs: Long, val endMs: Long?)
internal data class SystemLyricWindowFrame(val text: String, val nextPositionMs: Long? = null)

/** 按显示宽度和播放进度把长歌词切分成多段轮播，与具体音源和通知栏实现解耦。 */
internal class SystemLyricWindow private constructor(
    private val texts: List<String>,
    private val starts: List<Long>,
) {
    fun at(positionMs: Long): SystemLyricWindowFrame {
        val index = starts.indexOfLast { it <= positionMs }.coerceAtLeast(0)
        return SystemLyricWindowFrame(texts[index], starts.getOrNull(index + 1))
    }

    companion object {
        private val grapheme = Pattern.compile("\\X")
        private const val MIN_HOLD_MS = 1_500L

        fun create(line: SystemLyricInterval, width: Float, measure: (String) -> Float): SystemLyricWindow {
            val text = line.text.trim()
            val windows = split(text, width.coerceAtLeast(1f), measure)
            val duration = line.endMs?.minus(line.startMs)?.coerceAtLeast(0L) ?: 0L
            val count = (duration / MIN_HOLD_MS).coerceIn(1L, windows.size.toLong()).toInt()
            // 这句时长不够展示所有分段时，均匀抽选并保留首尾段，避免切字太快看不清。
            val selected = if (count == 1) listOf(windows.first()) else List(count) { index ->
                windows[(index.toDouble() * windows.lastIndex / (count - 1)).roundToInt()]
            }
            val extra = (duration - count * MIN_HOLD_MS).coerceAtLeast(0L)
            val weights = selected.mapIndexed { index, value ->
                measure(value).coerceAtLeast(1f).toDouble() * if (index == 0 || index == selected.lastIndex) 1.2 else 1.0
            }
            val total = weights.sum()
            var accumulated = 0.0
            val starts = selected.indices.map { index ->
                (line.startMs + index * MIN_HOLD_MS + (extra * accumulated / total).toLong())
                    .also { accumulated += weights[index] }
            }
            return SystemLyricWindow(selected, starts)
        }

        private fun split(text: String, width: Float, measure: (String) -> Float): List<String> {
            if (text.isEmpty() || measure(text) <= width) return listOf(text)
            // 按完整字符边界切分，避免把 Emoji、组合音标或代理对从中间劈开。
            val boundaries = mutableListOf(0)
            val matcher = grapheme.matcher(text)
            while (matcher.find()) boundaries += matcher.end()
            val result = mutableListOf<String>()
            var start = 0
            while (start < boundaries.lastIndex) {
                var low = start + 1
                var high = boundaries.lastIndex
                var end = low
                while (low <= high) {
                    val middle = (low + high) ushr 1
                    if (measure(text.substring(boundaries[start], boundaries[middle])) <= width) {
                        end = middle; low = middle + 1
                    } else high = middle - 1
                }
                if (end < boundaries.lastIndex) {
                    val insideWord = wordCodePoint(text.codePointBefore(boundaries[end])) &&
                        wordCodePoint(text.codePointAt(boundaries[end]))
                    val minimum = if (insideWord) start + 1 else start + (end - start) / 2 + 1
                    val preferred = (end downTo minimum).firstOrNull { index ->
                        val last = text.codePointBefore(boundaries[index])
                        Character.isWhitespace(last) || (!wordCodePoint(last) && !Character.isLetterOrDigit(last))
                    }
                    if (preferred != null) end = preferred
                }
                result += text.substring(boundaries[start], boundaries[end]).trim()
                if (end == boundaries.lastIndex) break
                var next = end
                while (next > start + 1 &&
                    measure(text.substring(boundaries[next - 1], boundaries[end])) <= width * 0.18f) next--
                // 分段重叠尽量不要从英文或阿拉伯/波斯单词中间切开，实在太长的词再按字符拆。
                while (next < end && next > 0 &&
                    wordCodePoint(text.codePointBefore(boundaries[next])) && wordCodePoint(text.codePointAt(boundaries[next]))) next++
                start = next.coerceAtLeast(start + 1)
                while (start < boundaries.lastIndex && text.substring(boundaries[start], boundaries[start + 1]).isBlank()) start++
            }
            return result
        }

        private fun wordCodePoint(value: Int): Boolean {
            if (value in 48..57 || value in 65..90 || value in 97..122) return true
            if (value == 0x200C || value == 0x200D || value == 0x0640) return true
            if (value <= 0xFFFF && isArabicOrPersianLetter(value.toChar())) return true
            return when (Character.getType(value)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                -> true
                else -> false
            }
        }
    }
}

internal fun SystemPlaybackPresentation.lyricIntervalAt(nowMs: Long): SystemLyricInterval? {
    val position = positionAt(nowMs)
    val index = lyrics.indexOfLast { it.timeMs <= position }
    val line = lyrics.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: return null
    val end = lyrics.getOrNull(index + 1)?.timeMs ?: durationMs.takeIf { it > line.timeMs }
    return SystemLyricInterval(line.text, line.timeMs, end)
}
