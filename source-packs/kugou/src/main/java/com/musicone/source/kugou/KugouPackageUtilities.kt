package com.musicone.source.kugou

import kotlinx.coroutines.CancellationException

internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) }
internal fun Throwable.asUserMessage(): String = message?.takeIf(String::isNotBlank) ?: "酷狗音乐请求失败，请重试"
internal fun <T> recommendationWindow(items: List<T>, limit: Int, page: Int): List<T> {
    return recommendationWindowIndices(items.size, limit, page).map(items::get)
}
internal fun recommendationWindowIndices(count: Int, limit: Int, page: Int): List<Int> {
    if (count <= 0) return emptyList()
    val size = minOf(limit.coerceAtLeast(1), count)
    val stride = if (count > size) size else maxOf(1, size / 2)
    val start = (Math.floorMod(page, count).toLong() * stride % count).toInt()
    return List(size) { index -> ((start.toLong() + index) % count).toInt() }
}

internal fun parseLrc(value: String): List<TimedLyric> {
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    return value.lineSequence().flatMap { line ->
        val text = line.replace(timestamp, "").trim()
        if (text.isBlank()) return@flatMap emptySequence()
        timestamp.findAll(line).map { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            val seconds = match.groupValues[2].toLongOrNull() ?: 0
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) { 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; else -> fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0 }
            TimedLyric((minutes * 60 + seconds) * 1000 + millis, text)
        }
    }.sortedBy(TimedLyric::timeMs).toList()
}
