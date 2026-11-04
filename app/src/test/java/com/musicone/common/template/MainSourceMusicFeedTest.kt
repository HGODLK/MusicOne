package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp
import kotlin.random.Random

class MainSourceMusicFeedTest {
    @Test
    fun feedColumnsKeepPhoneAndLandscapeTabletLayoutsDistinct() {
        assertEquals(2, mainSourceHomeColumns(360.dp, 800.dp))
        assertEquals(2, mainSourceHomeColumns(375.dp, 812.dp))
        assertEquals(3, mainSourceHomeColumns(800.dp, 1_200.dp))
        assertEquals(3, mainSourceHomeColumns(1_280.dp, 800.dp))
        assertEquals(1_180.dp, mainSourceHomeContentMaxWidth(1_280.dp, 800.dp))
    }

    private fun fixture(cards: String) = JSONObject("""{"v_shelf":[{"id":315,"v_niche":[{"v_card":[$cards]}]}]}""")

    private fun feedTrack(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = id,
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = "歌",
        previewUrl = "",
        catalogId = "1",
        mediaId = id.removePrefix("qq-"),
    )
}
