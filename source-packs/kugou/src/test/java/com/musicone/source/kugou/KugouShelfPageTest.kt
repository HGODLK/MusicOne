package com.musicone.source.kugou

import com.musicone.source.sdk.SourceTrack
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouShelfPageTest {
    private fun track(id: String) = SourceTrackDetails(SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", id), id), "kugou-$id")

    @Test fun 点击的歌曲找回所在页和页内位置() {
        val pages = listOf("a", "b", "c", "d", "e").map(::track).chunked(3)
        val (page, index) = requireNotNull(kugouShelfPageOf(pages, track("e")))
        assertEquals(listOf("kugou-d", "kugou-e"), page.map { it.stableId })
        assertEquals(1, index)
        assertEquals(0, kugouShelfPageOf(pages, track("a"))?.second)
        assertNull(kugouShelfPageOf(pages, track("x")))
    }
}
