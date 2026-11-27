package com.musicone.source.qq.internal

internal data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

internal data class TimedLyric(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
    // 0 表示旧版本缓存或未经过当前解析器确认，不能只看歌词非空就当成最新缓存
    val parserVersion: Int = 0,
    // 宿主约定的逐词注音文本，生成规则见 QqLyricRomaji.kt。
    val transliteration: String? = null,
)
