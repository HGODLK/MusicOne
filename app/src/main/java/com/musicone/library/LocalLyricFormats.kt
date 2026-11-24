package com.musicone

import java.io.File

// 保留同名文件已有的优先顺序，新格式作为后备。
internal val localLyricExtensions = setOf("lrc", "xlrc", "spl", "alrc", "eslrc", "qrc", "krc", "yrc",
    "ttml", "dfxp", "lys", "lyl", "lqe", "srt", "vtt", "ass", "ssa", "ksc", "lyrics", "lyricsfile")

internal fun localLyricExtension(name: String): String =
    name.substringAfterLast('.', "").lowercase().takeIf { it in localLyricExtensions } ?: "lrc"

internal fun parseLibraryLyrics(file: File): List<TimedLyric> =
    parseLocalLyricBytes(file.readBytes(), localLyricExtension(file.name))

internal fun parseLibraryLyrics(raw: String, extension: String = "lrc"): List<TimedLyric> {
    val text = raw.removePrefix("\uFEFF").trimStart()
    val hint = extension.lowercase()
    val first = text.lineSequence().firstOrNull().orEmpty().trimEnd()
    val format = when {
        first.equals("[Lyricify Quick Export]", true) -> "lqe"
        first.equals("[type:LyricifyLines]", true) -> "lyl"
        text.startsWith('<') && ttmlRoot.containsMatchIn(text) -> "ttml"
        text.startsWith('<') && text.contains("LyricContent=") -> "qrc"
        first == "WEBVTT" || first.startsWith("WEBVTT ") || first.startsWith("WEBVTT\t") -> "vtt"
        first.equals("[Script Info]", true) || first.equals("[Events]", true) -> "ass"
        kscEntry.containsMatchIn(text) -> "ksc"
        lyricsfileHeader.containsMatchIn(text) && lyricsfileTimeline.containsMatchIn(text) -> "lyricsfile"
        yrcSignature.containsMatchIn(text) -> "yrc"
        krcSignature.containsMatchIn(text) -> "krc"
        qrcSignature.containsMatchIn(text) -> "qrc"
        lysSignature.containsMatchIn(text) -> "lys"
        subtitleSignature.containsMatchIn(text) -> "srt"
        xlrcSignature.containsMatchIn(text) -> "xlrc"
        hint in setOf("lrc", "alrc", "eslrc") && hasSquareLyricWords(text) -> "spl"
        else -> hint
    }
    return when (format) {
        "qrc" -> parseLocalQrc(text)
        "krc", "yrc", "lys", "lyl" -> parseLocalMillisLyrics(text, format)
        "lqe" -> parseLocalLqe(text)
        "ttml", "dfxp" -> parseLocalTtml(text)
        "srt", "vtt" -> parseLocalSubtitleCues(text, format == "vtt")
        "ass", "ssa" -> parseLocalAss(text)
        "ksc" -> parseLocalKsc(text)
        "lyrics", "lyricsfile" -> parseLocalLyricsfile(text)
        else -> parseLocalLrc(text, format)
    }
}

private val ttmlRoot = Regex("<(?:[\\w.-]+:)?tt(?=[\\s>])")
private val xlrcSignature = Regex("(?m)^\\s*\\[\\d+:\\d+(?:[.:]\\d+)?]\\[v:")
private val subtitleSignature = Regex("(?m)^\\s*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{3}\\s+-->\\s+\\d")

internal fun lyricEnd(start: Long, duration: Long): Long? =
    if (start >= 0 && duration >= 0 && start <= Long.MAX_VALUE - duration) start + duration else null

internal fun localReading(text: String, reading: String?): String? = reading?.trim()?.takeIf {
    it.isNotBlank() && (it + text).none { c -> c in "{}|\r\n" }
}?.let { "{$text|$it}" }
