package com.musicone.source.qq.internal

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class QqPlaylistTitleTest {
    private val shell = MusicPlaylist("qq-12345", MusicSource.QQ, "歌单", "作者", "描述", 20,
        1, 2, "歌", emptyList(), artworkUrl = "https://example.com/cover.jpg")

    @Test fun restoresMixedTruncatedAndIntactCodePointsWithoutChangingPlaylistMetadata() {
        val original = "𝓐𝓑歌单🎵🎶"
        val directory = "\uD4D0𝓑歌单\uF3B5🎶"
        val playlist = shell.copy(title = directory)
        val restored = restoreQqPlaylistTitles(listOf(playlist)) { original }.single()
        assertEquals(playlist.copy(title = original, artworkMark = "𝓐"), restored)
        assertEquals(2, restored.artworkMark.length)
    }

    @Test fun decodesDetailEntitiesBeforeComparingTruncatedTitle() {
        val playlist = shell.copy(title = "\uF3B5 & 收藏")
        val restored = restoreQqPlaylistTitles(listOf(playlist)) { "&#x1F3B5; &amp; 收藏" }.single()
        assertEquals("🎵 & 收藏", restored.title)
        assertEquals("🎵", restored.artworkMark)
    }

    @Test fun normalChineseLatinAndIntactSupplementaryTitlesDoNotRequestDetails() {
        val titles = listOf("流行收藏", "Inner  Light", "𝓐歌单🎵", "普通가标题")
        val playlists = titles.map { shell.copy(title = it) }
        var calls = 0
        val restored = restoreQqPlaylistTitles(playlists) {
            calls++
            "详情标题"
        }
        assertEquals(0, calls)
        playlists.zip(restored).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test fun suspiciousRealCharactersAndMismatchedDetailsArePreserved() {
        val directory = "\uD4D0歌单"
        val playlist = shell.copy(title = directory)
        listOf(directory, "𝓐其他", "𝓐歌单追加", "𝓐歌", "", "𝄞歌单").forEach { detail ->
            assertSame(playlist, restoreQqPlaylistTitles(listOf(playlist)) { detail }.single())
        }
        val privateUse = shell.copy(title = "\uF000歌单")
        assertSame(privateUse, restoreQqPlaylistTitles(listOf(privateUse)) { privateUse.title }.single())
    }

    @Test fun missingOrFailedDetailsKeepTheOldTitleAndAllowOtherPlaylistsToRestore() {
        val playlists = (0..2).map { shell.copy(id = "qq-$it", title = "\uF3B5歌单") }
        val restored = restoreQqPlaylistTitles(playlists) {
            when (it.id) {
                "qq-0" -> null
                "qq-1" -> throw IllegalStateException("详情不可用")
                else -> "🎵歌单"
            }
        }
        assertSame(playlists[0], restored[0])
        assertSame(playlists[1], restored[1])
        assertEquals("🎵歌单", restored[2].title)
    }

    @Test fun cancellationPropagatesAndStopsSubsequentRequests() {
        val cancelled = CancellationException("取消标题查询")
        var calls = 0
        try {
            restoreQqPlaylistTitles(listOf(shell.copy(title = "\uF3B5"), shell.copy(title = "\uD4D0"))) {
                calls++
                throw cancelled
            }
            fail("取消必须向上传播")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertEquals(1, calls)
    }
}
