package com.musicone.demo

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class TimedLyric(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
    // 零表示来源未经当前解析器确认，不能仅凭非空内容升级旧缓存。
    val parserVersion: Int = 0,
)
