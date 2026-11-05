package com.musicone

/** 歌词页除原文外还显示什么；只改变展示，缓存里的翻译和注音都保留，切换时不用重新请求。 */
internal enum class LyricDisplayContent(val storedValue: String, val label: String, val description: String) {
    TRANSLATION("translation", "原文和翻译", "显示歌词原文和翻译"),
    ROMAJI("romaji", "原文、注音和翻译", "在可用的歌词上方显示注音"),
    ORIGINAL("original", "仅原文", "只显示歌词原文，系统歌词也不显示翻译");

    companion object {
        fun fromStored(value: String?): LyricDisplayContent = entries.firstOrNull { it.storedValue == value } ?: TRANSLATION
    }
}

/** 在进入歌词窗口前统一裁剪，列表高度、准备判定和绘制读到的都是同一份内容。 */
internal fun lyricDisplayLines(lines: List<TimedLyric>, content: LyricDisplayContent): List<TimedLyric> = when (content) {
    LyricDisplayContent.ROMAJI -> lines
    LyricDisplayContent.TRANSLATION -> lines.map { if (it.transliteration == null) it else it.copy(transliteration = null) }
    LyricDisplayContent.ORIGINAL -> lines.map {
        if (it.transliteration == null && it.translation == null) it else it.copy(translation = null, transliteration = null)
    }
}
