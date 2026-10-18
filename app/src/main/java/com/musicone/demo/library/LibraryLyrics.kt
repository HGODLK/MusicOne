package com.musicone.demo

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.charset.Charset

/** 原文保留，偏移只在装载时间线时应用一次，逐字与点击跳转使用同一结果。 */
internal fun MusicLibraryStore.lyrics(track: MusicTrack): List<TimedLyric> {
    val (manual, offset) = lyricAsset(track.id)
    val file = manual?.let(::File)?.takeIf(File::isFile)
        ?: defaultLyrics(track.id)?.let(::File)?.takeIf(File::isFile)
        ?: File(assets, "lyrics/${track.id}.default.lrc").takeIf(File::isFile)
        ?: return emptyList()
    return parseLibraryLyrics(file.readText()).map { line -> line.copy(
        timeMs = line.timeMs + offset,
        words = line.words.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) },
    ) }
}

internal fun parseLibraryLyrics(raw: String): List<TimedLyric> {
    val time = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val word = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    fun MatchResult.millis(): Long = groupValues[1].toLong() * 60_000 + groupValues[2].toLong() * 1_000 +
        groupValues[3].padEnd(3, '0').take(3).toLongOrNull().orZero()
    val fileOffset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return raw.lineSequence().flatMap { row ->
        val stamps = time.findAll(row).toList()
        val body = row.substringAfterLast(']').trim()
        val words = word.findAll(body).toList()
        val text = body.replace(word, "")
        stamps.map { stamp ->
            val segments = words.mapIndexedNotNull { index, match ->
                val next = words.getOrNull(index + 1)
                val content = body.substring(match.range.last + 1, next?.range?.first ?: body.length)
                content.takeIf(String::isNotEmpty)?.let { LyricWord(match.millis() + fileOffset, (next?.millis() ?: match.millis() + 500) + fileOffset, it) }
            }
            TimedLyric(stamp.millis() + fileOffset, text, words = segments)
        }
    }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
}
private fun Long?.orZero() = this ?: 0L

/** 读取常见 ID3 USLT 和 FLAC Vorbis 歌词，不按扩展名决定音频能否播放。 */
internal fun readEmbeddedLocalLyrics(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
        val header = ByteArray(10)
        if (input.read(header) < 10) return@use null
        if (String(header, 0, 3, Charsets.US_ASCII) == "ID3") {
            val length = (6..9).fold(0) { size, i -> (size shl 7) or (header[i].toInt() and 127) }.coerceAtMost(4 * 1024 * 1024)
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) { val count = input.read(bytes, read, length - read); if (count < 0) break; read += count }
            var cursor = 0
            while (cursor + 10 <= read) {
                val id = String(bytes, cursor, 4, Charsets.US_ASCII)
                val size = (4..7).fold(0) { value, i -> (value shl if (header[3].toInt() >= 4) 7 else 8) or (bytes[cursor + i].toInt() and if (header[3].toInt() >= 4) 127 else 255) }
                if (size <= 0 || cursor + 10L + size > read) break
                if (id == "USLT" && size > 4) {
                    val encoding = bytes[cursor + 10].toInt()
                    val charset = when (encoding) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                    val start = cursor + 14
                    val text = String(bytes, start, size - 4, charset)
                    return@use text.substringAfter('\u0000', text).trim('\u0000', ' ', '\n', '\r')
                }
                cursor += 10 + size
            }
            null
        } else if (String(header, 0, 4, Charsets.US_ASCII) == "fLaC") {
            // 重新打开以完整读取元数据块，避免第一次探测吃掉块头。
            context.contentResolver.openInputStream(uri)?.buffered()?.use { flac ->
                flac.skip(4)
                repeat(128) {
                    val type = flac.read(); if (type < 0) return@use null
                    val size = (flac.read() shl 16) or (flac.read() shl 8) or flac.read()
                    if (size !in 0..4 * 1024 * 1024) return@use null
                    val block = ByteArray(size)
                    var filled = 0
                    while (filled < size) { val count = flac.read(block, filled, size - filled); if (count < 0) return@use null; filled += count }
                    if (type and 127 == 4) {
                        val buffer = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val vendor = buffer.int; if (vendor < 0 || vendor > buffer.remaining()) return@use null
                        buffer.position(buffer.position() + vendor)
                        repeat(buffer.int.coerceIn(0, 10000)) {
                            val length = buffer.int; if (length < 0 || length > buffer.remaining()) return@use null
                            val comment = ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
                            if (comment.substringBefore('=').uppercase() in setOf("LYRICS", "UNSYNCEDLYRICS")) return@use comment.substringAfter('=')
                        }
                    }
                    if (type and 128 != 0) return@use null
                }
                null
            }
        } else null
    }
}.getOrNull()
