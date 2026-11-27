package com.musicone.source.netease

internal data class NeteaseLyricWord(val startMs: Long, val endMs: Long, val text: String)

internal data class NeteaseLyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<NeteaseLyricWord> = emptyList(),
    // 0 表示旧版本缓存或未经过当前解析器确认，不能只看歌词非空就当成最新缓存
    val parserVersion: Int = 0,
)
