package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLyricsTopPullTest {
    @Test
    fun pullingDownClosesOnlyAtTop() {
        assertEquals(LyricsTopPullSplit(-.1f, 0f), lyricsTopPullSplit(100f, 1f, 1000f, atTop = true))
        assertEquals(LyricsTopPullSplit(0f, 100f), lyricsTopPullSplit(100f, 1f, 1000f, atTop = false))
    }

    @Test
    fun pushingUpReopensBeforeScrollingList() {
        val partial = lyricsTopPullSplit(-100f, .5f, 1000f, atTop = true)
        assertEquals(.1f, partial.progress, .0001f)
        assertEquals(0f, partial.list, .0001f)
        val overflow = lyricsTopPullSplit(-100f, .95f, 1000f, atTop = true)
        assertEquals(.05f, overflow.progress, .0001f)
        assertEquals(-50f, overflow.list, .01f)
    }

    @Test
    fun landscapeDistanceOnlyCountsPullAtTop() {
        assertEquals(30f, landscapeLyricsTopPullDistance(10f, 20f, atTop = true), 0f)
        assertEquals(0f, landscapeLyricsTopPullDistance(10f, -20f, atTop = true), 0f)
        assertEquals(0f, landscapeLyricsTopPullDistance(50f, 20f, atTop = false), 0f)
    }
}
