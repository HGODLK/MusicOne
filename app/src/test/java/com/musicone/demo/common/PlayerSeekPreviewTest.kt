package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerSeekPreviewTest {
    @Test fun previewClampsPositionAndCanBeClearedOnTrackChange() {
        val preview = PlayerSeekPreview()
        preview.update(.5f, 120_000L)
        assertEquals(60_000L, preview.position.value)
        preview.update(2f, 120_000L)
        assertEquals(120_000L, preview.position.value)
        preview.clear()
        assertNull(preview.position.value)
    }

    @Test fun progressTapLyricSeekKeepsOriginalPositionAndDoesNotReplaceLyricClick() {
        val preview = PlayerSeekPreview()
        preview.requestLyricSeek("track", .25f)
        val lyricClick = preview.lyricSeek.value

        preview.requestProgressLyricSeek("track", -20L, 1.5f)

        assertSame(lyricClick, preview.lyricSeek.value)
        assertEquals(0L, preview.progressLyricSeek.value?.fromPositionMs)
        assertEquals(1f, preview.progressLyricSeek.value?.fraction ?: 0f, .0001f)
        assertEquals(1L, preview.progressLyricSeek.value?.revision)
    }

    @Test fun onlyDraggingProgressPublishesRealtimeLyricPreview() {
        assertFalse(shouldPreviewLyricsDuringProgressChange(dragging = false, dragged = false))
        assertTrue(shouldPreviewLyricsDuringProgressChange(dragging = true, dragged = false))
        assertTrue(shouldPreviewLyricsDuringProgressChange(dragging = false, dragged = true))
    }

    @Test fun fullAudioValidationRejectsShortPreviewsAndUnknownDuration() {
        assertTrue(qqMediaDurationMatches(301_300, 301_000))
        assertFalse(qqMediaDurationMatches(60_000, 301_000))
        assertFalse(qqMediaDurationMatches(0, 301_000))
        assertFalse(qqMediaDurationMatches(301_000, 0))
    }
}
