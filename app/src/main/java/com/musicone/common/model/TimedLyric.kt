package com.musicone

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class TimedLyric(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
    // 0 表示旧版缓存没记录解析器版本，不能只因为有内容就当成最新版缓存。
    val parserVersion: Int = 0,
    // 为 true 表示已经加过用户的歌词时间偏移，展示层别再重复加一次。
    val libraryTimeline: Boolean = false,
    // 音源包提供的逐词注音，格式见 LyricRuby.kt；时间相对字组起点，不受偏移影响。
    val transliteration: String? = null,
)
