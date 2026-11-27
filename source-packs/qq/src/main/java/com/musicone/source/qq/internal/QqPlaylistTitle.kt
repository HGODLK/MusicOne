package com.musicone.source.qq.internal

import java.util.concurrent.CancellationException

/** 目录接口有时会把带花体字或 emoji 的歌单名截断，这里用详情接口返回的完整标题校正。 */
internal fun restoreQqPlaylistTitles(
    playlists: List<MusicPlaylist>,
    detailTitle: (MusicPlaylist) -> String?,
): List<MusicPlaylist> = playlists.map { playlist ->
    if (playlist.title.none { it.code in 0xD400..0xD7FF || it.code in 0xF000..0xFAFF }) return@map playlist
    val original = try {
        detailTitle(playlist)?.let(::decodeQqPlaylistDescription)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return@map playlist
    if (matchesTruncatedQqTitle(original, playlist.title)) playlist.copy(
        title = original,
        artworkMark = original.substring(0, original.offsetByCodePoints(0, 1)),
    ) else playlist
}

private fun matchesTruncatedQqTitle(original: String, directory: String): Boolean {
    var sourceIndex = 0
    var directoryIndex = 0
    var restored = false
    while (sourceIndex < original.length) {
        val codePoint = original.codePointAt(sourceIndex)
        val length = Character.charCount(codePoint)
        if (directory.regionMatches(directoryIndex, original, sourceIndex, length)) {
            directoryIndex += length
        } else if ((codePoint in 0x1D400..0x1D7FF || codePoint in 0x1F000..0x1FAFF) &&
            directoryIndex < directory.length && directory[directoryIndex] == codePoint.toChar()) {
            directoryIndex++
            restored = true
        } else return false
        sourceIndex += length
    }
    return restored && directoryIndex == directory.length
}
