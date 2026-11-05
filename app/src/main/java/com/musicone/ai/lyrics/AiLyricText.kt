package com.musicone

import org.json.JSONArray
import org.json.JSONObject

private val aiLyricLineStamp = Regex("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
private val aiLyricWordStamp = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,6})?>")
private val aiLyricMetadata = Regex("^\\[[A-Za-z]+:[^]]*]$")
private val aiLyricNoise = Regex("[\\s\\p{P}\\p{S}]+")

internal const val AI_LYRIC_MAX_LINES = 400
internal const val AI_LYRIC_MAX_BYTES = 64_000

internal data class AiLyricDraft(val content: String, val lines: List<TimedLyric>, val estimated: Boolean)

internal fun aiLyricStamp(ms: Long): String {
    val value = ms.coerceAtLeast(0L)
    return "[%02d:%02d.%02d]".format(value / 60_000, value / 1000 % 60, value % 1000 / 10)
}

private fun aiLyricWordStamp(ms: Long): String = aiLyricStamp(ms).replace('[', '<').replace(']', '>')

/** 把歌词写成本地 LRC：逐字用 <> 标记，注音和翻译各占一行同时间戳，宿主解析器可原样读回。 */
internal fun aiLyricsToLrc(lines: List<TimedLyric>, includeWords: Boolean): String = buildString {
    lines.forEach { line ->
        val stamp = aiLyricStamp(line.timeMs)
        append(stamp)
        // 逐字拼起来必须正好是整行原文，否则只写整行，避免读回后正文缺字。
        if (includeWords && line.words.isNotEmpty() && line.words.joinToString("") { it.text }.trim() == line.text) {
            line.words.forEach { append(aiLyricWordStamp(it.startMs)).append(it.text) }
            append(aiLyricWordStamp(line.words.last().endMs))
        } else append(line.text)
        append('\n')
        lyricRubyAnchoredText(line)?.let { append(stamp).append(it).append('\n') }
        line.translation?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.forEach { append(stamp).append(it).append('\n') }
    }
}

/**
 * 解析 AI 提交的歌词文本：要么每行都带 [mm:ss.xx] 时间，要么都不带。
 * 都不带时按歌曲时长平均分配并标记为估算；AI 提交的文本一律不能带逐字时间。
 */
internal fun aiLyricDraft(raw: String, durationMs: Long): AiLyricDraft {
    if (raw.toByteArray().size > AI_LYRIC_MAX_BYTES) throw AiToolInputFailure("lyrics_too_long", "歌词过长，请只提交这一首歌的歌词")
    if (aiLyricWordStamp.containsMatchIn(raw)) throw AiToolInputFailure("word_timing_not_allowed", "逐字时间只能来自音源，提交的歌词不能带<mm:ss>逐字标记")
    val rows = raw.lineSequence().map { it.trim().removePrefix("﻿") }
        .filter { it.isNotEmpty() && !aiLyricMetadata.matches(it) }.toList()
    if (rows.isEmpty()) throw AiToolInputFailure("empty_lyrics", "歌词为空")
    if (rows.size > AI_LYRIC_MAX_LINES) throw AiToolInputFailure("lyrics_too_long", "歌词超过${AI_LYRIC_MAX_LINES}行")
    val timed = rows.count { aiLyricLineStamp.containsMatchIn(it) }
    val (content, estimated) = when (timed) {
        rows.size -> rows.joinToString("\n") to false
        0 -> {
            val duration = durationMs.takeIf { it > 0 } ?: (rows.size * 4_000L)
            rows.mapIndexed { index, row -> aiLyricStamp(duration * index / rows.size) + row }.joinToString("\n") to true
        }
        else -> throw AiToolInputFailure("mixed_timing", "歌词要么每行都带[mm:ss.xx]时间，要么都不带，不能混用")
    }
    val lines = parseLibraryLyrics(content)
    if (lines.isEmpty()) throw AiToolInputFailure("invalid_lyrics", "歌词无法解析，请检查时间格式")
    return AiLyricDraft(content + "\n", lines, estimated)
}

internal fun aiLyricNormalize(value: String) = aiLyricNoise.replace(value, "").lowercase()

/** 统计歌词行在资料中逐字出现的比例，用于确认歌词确实来自用户提供的文本或抓取的网页。 */
internal fun aiLyricCoverage(lines: List<TimedLyric>, corpus: String): Float {
    val normalized = aiLyricNormalize(corpus)
    val texts = lines.map { aiLyricNormalize(it.text) }.filter { it.length >= 2 }
    if (texts.isEmpty()) return 0f
    return texts.count { normalized.contains(it) }.toFloat() / texts.size
}

/** 提取网页抓取结果里的全部文字，忽略 JSON 结构和转义。 */
internal fun aiLyricPageText(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().joinToString("\n") { aiLyricPageText(value.opt(it)) }
    is JSONArray -> (0 until value.length()).joinToString("\n") { aiLyricPageText(value.opt(it)) }
    is String -> value
    else -> ""
}
