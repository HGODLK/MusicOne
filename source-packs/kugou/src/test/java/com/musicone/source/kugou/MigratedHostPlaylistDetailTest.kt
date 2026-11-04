package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostPlaylistDetailTest {
    @Test fun kugouPreviewTracksDoNotCountAsCompletePlaylist() {
        assertFalse(hasCompleteKugouTrackList(3, 20))
        assertTrue(hasCompleteKugouTrackList(20, 20))
    }

}
