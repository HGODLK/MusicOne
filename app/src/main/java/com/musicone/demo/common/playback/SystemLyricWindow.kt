package com.musicone.demo

import java.util.regex.Pattern
import kotlin.math.roundToInt

internal data class SystemLyricInterval(val text: String, val startMs: Long, val endMs: Long?)
internal data class SystemLyricWindowFrame(val text: String, val nextPositionMs: Long? = null)

/** 只按通用歌词、宽度预算和播放位置分段，不认识音源与系统通知。 */
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
            // 时间不足时均匀选段并保留首尾，不以加速闪字换取完整轮播。
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
            // 字素边界保留代理对、组合音标和连写 Emoji。
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
                    val insideWord = asciiWord(text.codePointBefore(boundaries[end])) &&
                        asciiWord(text.codePointAt(boundaries[end]))
                    val minimum = if (insideWord) start + 1 else start + (end - start) / 2 + 1
                    val preferred = (end downTo minimum).firstOrNull { index ->
                        val last = text.codePointBefore(boundaries[index])
                        Character.isWhitespace(last) || !Character.isLetterOrDigit(last)
                    }
                    if (preferred != null) end = preferred
                }
                result += text.substring(boundaries[start], boundaries[end]).trim()
                if (end == boundaries.lastIndex) break
                var next = end
                while (next > start + 1 &&
                    measure(text.substring(boundaries[next - 1], boundaries[end])) <= width * 0.18f) next--
                // 重叠不能从英文单词中间开始；极长单词才按字素拆分。
                while (next < end && next > 0 &&
                    asciiWord(text.codePointBefore(boundaries[next])) && asciiWord(text.codePointAt(boundaries[next]))) next++
                start = next.coerceAtLeast(start + 1)
                while (start < boundaries.lastIndex && text.substring(boundaries[start], boundaries[start + 1]).isBlank()) start++
            }
            return result
        }

        private fun asciiWord(value: Int) = value in 48..57 || value in 65..90 || value in 97..122
    }
}

internal fun SystemPlaybackPresentation.lyricIntervalAt(nowMs: Long): SystemLyricInterval? {
    val position = positionAt(nowMs)
    val index = lyrics.indexOfLast { it.timeMs <= position }
    val line = lyrics.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: return null
    val end = lyrics.getOrNull(index + 1)?.timeMs ?: durationMs.takeIf { it > line.timeMs }
    return SystemLyricInterval(line.text, line.timeMs, end)
}
