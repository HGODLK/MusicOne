package com.musicone

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class TimedLyric(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
    // 零表示来源未经当前解析器确认，不能仅凭非空内容升级旧缓存。
    val parserVersion: Int = 0,
    // 资料库时间线已应用用户整行与逐字偏移，展示层不能再次移动。
    val libraryTimeline: Boolean = false,
    // 音源包提供的逐词注音，格式见 LyricRuby.kt；时间相对字组起点，不受偏移影响。
    val transliteration: String? = null,
)
