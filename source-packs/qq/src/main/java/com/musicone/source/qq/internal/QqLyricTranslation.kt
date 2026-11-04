package com.musicone.source.qq.internal

internal fun mergeLyricTranslation(
    original: List<TimedLyric>,
    translated: List<TimedLyric>,
    toleranceMs: Long = 600L,
): List<TimedLyric> {
    if (original.isEmpty() || translated.isEmpty()) return original
    return original.map { line ->
        val translation = translated.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
            ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= toleranceMs }
            ?.text
            ?.takeIf {
                it.isNotBlank() &&
                it != line.text &&
                it.trim() != "//" &&
                !it.matches(Regex("^/+$"))
            }
        line.copy(translation = translation)
    }
}

