package com.musicone.demo

import java.util.concurrent.CancellationException

/** 目录接口会把部分数学字母截成 16 位；只有详情原文能完整解释该截断时才校正。 */
internal fun restoreQqPlaylistTitles(
    playlists: List<MusicPlaylist>,
    detailTitle: (MusicPlaylist) -> String?,
): List<MusicPlaylist> = playlists.map { playlist ->
    if (playlist.title.none { it.code in 0xD400..0xD7FF }) return@map playlist
    val original = try {
        detailTitle(playlist)?.let(::decodeQqPlaylistDescription)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return@map playlist
    var containsMath = false
    val truncated = buildString {
        var index = 0
        while (index < original.length) {
            val codePoint = original.codePointAt(index)
            if (codePoint in 0x1D400..0x1D7FF) {
                containsMath = true
                append(codePoint.toChar())
            } else appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }
    if (containsMath && truncated == playlist.title) playlist.copy(
        title = original,
        artworkMark = original.substring(0, original.offsetByCodePoints(0, 1)),
    ) else playlist
}
