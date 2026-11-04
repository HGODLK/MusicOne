package com.musicone.source.qq.internal

internal fun parseLrc(value: String): List<TimedLyric> {
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    return value.lineSequence().flatMap { line ->
        val text = line.replace(timestamp, "").trim()
        if (text.isBlank()) return@flatMap emptySequence()
        timestamp.findAll(line).map { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3]
            val milliseconds = when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
                else -> fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
            }
            TimedLyric((minutes * 60L + seconds) * 1_000L + milliseconds, text)
        }
    }.sortedBy(TimedLyric::timeMs).toList()
}
