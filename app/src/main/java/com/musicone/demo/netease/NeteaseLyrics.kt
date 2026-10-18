package com.musicone.demo

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal const val CURRENT_NETEASE_LYRIC_PARSER_VERSION = 2

private val neteaseYrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
private val neteaseYrcWord = Regex("\\((\\d+),(\\d+),(\\d+)\\)")

internal fun loadNeteaseLyrics(rich: () -> JSONObject, legacy: () -> JSONObject,
    richUnavailable: () -> Unit = {}): List<TimedLyric> {
    try {
        parseNeteaseLyrics(rich()).takeIf { it.isNotEmpty() }?.let { return it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 新接口暂不可用时继续提供普通歌词，但不标记为已确认无逐字数据。
    }
    richUnavailable()
    return parseNeteaseLyrics(legacy(), confirmed = false)
}

/** YRC 词段使用歌曲绝对毫秒；保留原始分词、空格和标点供共用渲染器定位。 */
internal fun parseNeteaseYrc(source: String): List<TimedLyric> = source.lineSequence().mapNotNull { raw ->
    val line = neteaseYrcLine.matchEntire(raw.trim()) ?: return@mapNotNull null
    val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
    val duration = line.groupValues[2].toLongOrNull() ?: return@mapNotNull null
    if (duration > Long.MAX_VALUE - start) return@mapNotNull null
    val body = line.groupValues[3]
    val timings = neteaseYrcWord.findAll(body).toList()
    if (timings.isEmpty()) return@mapNotNull null
    val words = ArrayList<LyricWord>(timings.size)
    for ((index, timing) in timings.withIndex()) {
        val wordStart = timing.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val wordDuration = timing.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        if (wordDuration > Long.MAX_VALUE - wordStart) return@mapNotNull null
        if (words.lastOrNull()?.let { wordStart < it.startMs } == true) return@mapNotNull null
        val textStart = if (index == 0) 0 else timing.range.last + 1
        val textEnd = timings.getOrNull(index + 1)?.range?.first ?: body.length
        // 首个标记前偶有未计时前缀，归入首词，避免正文与词段字符范围错位。
        val text = if (index == 0) body.substring(0, timing.range.first) +
            body.substring(timing.range.last + 1, textEnd) else body.substring(textStart, textEnd)
        if (text.isNotEmpty()) words += LyricWord(wordStart, wordStart + wordDuration, text)
    }
    val text = words.joinToString("") { it.text }
    if (text.isBlank() || text.trim().matches(Regex("^/+$"))) return@mapNotNull null
    TimedLyric(start, text, words = words)
}.sortedBy(TimedLyric::timeMs).toList()

/** 字段名称不能决定内容格式，网易云的逐字配套翻译也可能仍是 LRC。 */
internal fun parseNeteaseLyrics(response: JSONObject, confirmed: Boolean = true): List<TimedLyric> {
    fun lyric(key: String) = response.optJSONObject(key)?.optString("lyric").orEmpty()
    val verbatim = parseNeteaseYrc(lyric("yrc"))
    val original = verbatim.ifEmpty { parseLrc(lyric("lrc")) }
    val richTranslation = lyric("ytlrc")
    val translated = parseNeteaseYrc(richTranslation).ifEmpty { parseLrc(richTranslation) }
        .ifEmpty { parseLrc(lyric("tlyric")) }
    return alignNeteaseTranslation(original, translated).map {
        // code=200 且只有 LRC 并不能证明该曲永久没有逐字歌词。
        it.copy(parserVersion = if (confirmed && verbatim.isNotEmpty()) CURRENT_NETEASE_LYRIC_PARSER_VERSION else 0)
    }
}

/** 有序一对一配对；只在多个互为最近的样本支持时修正副歌词整体偏移。 */
internal fun alignNeteaseTranslation(original: List<TimedLyric>, translated: List<TimedLyric>): List<TimedLyric> {
    val candidates = translated.filter { it.text.isNotBlank() && !it.text.trim().matches(Regex("^/+$")) }
    if (original.isEmpty() || candidates.isEmpty()) return original
    val offset = neteaseTranslationOffset(original, candidates)
    val result = original.toMutableList()
    var target = 0
    var candidate = 0
    while (target < original.size && candidate < candidates.size) {
        val delta = candidates[candidate].timeMs - offset - original[target].timeMs
        when {
            delta > 1_500L -> target++
            delta < -1_500L -> candidate++
            // 相邻短句取更近者，漏译时不能把下一句翻译消费给当前句。
            target + 1 < original.size && kotlin.math.abs(candidates[candidate].timeMs - offset -
                original[target + 1].timeMs) < kotlin.math.abs(delta) -> target++
            candidate + 1 < candidates.size && kotlin.math.abs(candidates[candidate + 1].timeMs - offset -
                original[target].timeMs) < kotlin.math.abs(delta) -> candidate++
            else -> {
                val text = candidates[candidate].text.trim().takeIf { it != original[target].text.trim() }
                result[target] = original[target].copy(translation = text)
                target++
                candidate++
            }
        }
    }
    return result
}

private fun neteaseTranslationOffset(original: List<TimedLyric>, translated: List<TimedLyric>): Long {
    val differences = original.mapIndexedNotNull { index, line ->
        val nearest = translated.indices.minByOrNull { kotlin.math.abs(translated[it].timeMs - line.timeMs) }
            ?: return@mapIndexedNotNull null
        val reverse = original.indices.minByOrNull { kotlin.math.abs(original[it].timeMs - translated[nearest].timeMs) }
        (translated[nearest].timeMs - line.timeMs).takeIf { reverse == index && kotlin.math.abs(it) <= 5_000L }
    }.sorted()
    if (differences.size < 3) return 0L
    val median = differences[differences.size / 2]
    // 分散的差值可能来自缺行或不同版本，不能据此移动整条副歌词时间轴。
    return median.takeIf { value -> differences.count { kotlin.math.abs(it - value) <= 200L } * 4 >= differences.size * 3 } ?: 0L
}
