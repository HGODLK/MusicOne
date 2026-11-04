package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MainSourceMusicFeedSongShelvesTest {
    @Test fun previousMergedSnapshotsRequireOneFreshRestore() {
        assertTrue(requiresMainSourceFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song-shelf"}"""))))
        assertTrue(requiresMainSourceFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song-shelf","shelfLayoutVersion":2}"""))))
        assertFalse(requiresMainSourceFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song-shelf","shelfLayoutVersion":3}"""))))
        assertFalse(requiresMainSourceFeedShelfLayoutRefresh(listOf(JSONObject("""{"kind":"song"}"""))))
    }

    private fun shelf(id: String, page: List<MainSourceHomeCard.Song>) =
        MainSourceHomeCard.SongShelf(id, "官方推荐", listOf(page))

    private fun page(vararg ids: String) = ids.map(::song)

    private fun song(id: String) = MainSourceHomeCard.Song(MusicTrack(
        id = "qq-$id", source = MusicSource.QQ, title = id, artists = "歌手", album = "",
        durationMs = 0, artworkStart = 0, artworkEnd = 0, artworkMark = id, previewUrl = "",
    ), "推荐语")
}
