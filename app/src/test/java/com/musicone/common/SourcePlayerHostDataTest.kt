package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.musicone.source.sdk.SourceLyricTiming
import com.musicone.source.sdk.SourcePlayerLyricsState
import com.musicone.source.sdk.SourcePlayerOpening
import com.musicone.source.sdk.SourcePlayerSharedScale
import com.musicone.source.sdk.TrackIdentity
import com.musicone.sourceintegration.SourcePlayerDisplayedTracks
import com.musicone.sourceintegration.sourcePlayerBarCopyScale
import com.musicone.sourceintegration.sourcePlayerLyrics
import com.musicone.sourceintegration.sourcePlayerSharedPose
import com.musicone.sourceintegration.usesGlass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlayerHostDataTest {
    private val miniTitle = Rect(80f, 700f, 280f, 720f)
    private val pageTitle = Size(600f, 40f)

    @Test fun barCopyStartsAtMiniSizeAndEndsAtPageHeight() {
        for (scale in SourcePlayerSharedScale.entries) {
            val start = sourcePlayerSharedPose(miniTitle, 0f, Offset.Zero, pageTitle, 0, 0f, scale, null)
            val (x0, y0) = sourcePlayerBarCopyScale(start, miniTitle.size, pageTitle, scale)
            // 起点副本就是迷你条原样，不能有任何缩放。
            assertEquals(1f, x0, 1e-4f)
            assertEquals(1f, y0, 1e-4f)
        }
        val end = sourcePlayerSharedPose(miniTitle, 0f, Offset.Zero, pageTitle, 0, 1f,
            SourcePlayerSharedScale.UNIFORM, null)
        val (x1, y1) = sourcePlayerBarCopyScale(end, miniTitle.size, pageTitle, SourcePlayerSharedScale.UNIFORM)
        // 终点与包元素同高，文字两轴同比，不拉伸。
        assertEquals(2f, y1, 1e-4f)
        assertEquals(y1, x1, 1e-4f)
    }

    @Test fun glassOnlyForUnrotatedExpandingPages() {
        val expanding = SourcePlayerOpening(480, true, emptySet(), pageFade = false, glass = true)
        assertTrue(expanding.usesGlass(0))
        assertFalse(expanding.usesGlass(90))
        assertFalse(expanding.copy(pageFade = true).usesGlass(0))
        assertFalse(expanding.copy(glass = false).usesGlass(0))
    }

    @Test fun lyricsOnlyExposeLinesWhenReady() {
        val line = TimedLyric(1000, "第一行", "translation", listOf(LyricWord(1000, 1500, "第一")))
        val track = MusicTrack("a", MusicSource.LOCAL, "歌名", "艺人", "专辑", 1000, 1, 2, "♪", "")
            .copy(lyrics = listOf(line))
        val timing = { _: MusicTrack -> SourceLyricTiming(120, 40) }
        val ready = sourcePlayerLyrics(track, LyricLoadState.READY, timing)
        assertEquals(SourcePlayerLyricsState.READY, ready.state)
        assertEquals("第一行", ready.lines.single().text)
        assertEquals(1500L, ready.lines.single().words.single().endMs)
        assertEquals(120L, ready.lineLeadMs)
        assertEquals(40L, ready.wordLeadMs)
        // 加载中不能沿用曲目上残留的旧歌词。
        val loading = sourcePlayerLyrics(track, LyricLoadState.LOADING, timing)
        assertEquals(SourcePlayerLyricsState.LOADING, loading.state)
        assertTrue(loading.lines.isEmpty())
        assertEquals(SourcePlayerLyricsState.UNAVAILABLE, sourcePlayerLyrics(null, LyricLoadState.READY, timing).state)
    }

    @Test fun displayedTracksAllowQueueSnapshotUntilReplaced() {
        val page = Any()
        val other = TrackIdentity("netease", null, "1")
        try {
            assertFalse(SourcePlayerDisplayedTracks.allows("qq", other))
            SourcePlayerDisplayedTracks.recordQueue(page, "qq", setOf(other))
            assertTrue(SourcePlayerDisplayedTracks.allows("qq", other))
            // 其他音源的公共组件不受影响。
            assertFalse(SourcePlayerDisplayedTracks.allows("kugou", other))
            SourcePlayerDisplayedTracks.recordQueue(page, "qq", emptySet())
            assertFalse(SourcePlayerDisplayedTracks.allows("qq", other))
            SourcePlayerDisplayedTracks.record(page, "qq", other)
            assertTrue(SourcePlayerDisplayedTracks.allows("qq", other))
        } finally {
            SourcePlayerDisplayedTracks.release(page)
        }
        assertFalse(SourcePlayerDisplayedTracks.allows("qq", other))
    }
}
