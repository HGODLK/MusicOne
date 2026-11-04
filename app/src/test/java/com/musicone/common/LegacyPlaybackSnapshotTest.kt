package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyPlaybackSnapshotTest {
    @Test fun oldStorageKeysRestoreOrderPositionAndRadioWithoutTemporaryTickets() {
        val first = track("qq-first")
        val second = track("qq-second")
        val raw = JSONObject().put("version", 1).put("currentTrackId", second.id)
            .put("positionMs", 9_000L).put("shuffle", true).put("repeatMode", "OFF")
            .put("qqRadioActive", true).put("radioProtocolVersion", 1)
            .put("originalQueueIds", JSONArray(listOf(first.id, second.id)))
            .put("queue", JSONArray(listOf(first.toStoredTrackJson(), second.toStoredTrackJson())))
        val restored = requireNotNull(raw.toLegacyPlaybackSnapshot())
        assertEquals(listOf(first.id, second.id), restored.queue.map { it.id })
        assertEquals(second.id, restored.currentTrackId)
        assertEquals(9_000L, restored.positionMs)
        assertTrue(restored.shuffle)
        assertTrue(restored.radioActive)
        assertEquals(RepeatMode.OFF, restored.repeatMode)
        assertTrue(restored.queue.all { it.previewUrl.isEmpty() && it.lyrics.isEmpty() })
    }
    @Test fun missingOrUnknownOldSnapshotCannotInventAnActivePlayer() {
        assertNull(JSONObject().toLegacyPlaybackSnapshot())
        assertNull(JSONObject().put("version", 1).put("queue", JSONArray()).toLegacyPlaybackSnapshot())
        assertNull(JSONObject().put("version", 1).put("qqRadioActive", true)
            .put("radioProtocolVersion", 0).toLegacyPlaybackSnapshot())
    }
    @Test fun newMixedSnapshotKeepsDifferentSourcesAndPublicRadioState() {
        val first = track("qq-first")
        val other = first.copy(id = "third-party-second", source = MusicSource.fromId("org.example.other"))
        val json = JSONObject(requireNotNull(MixedPlaybackSnapshotEncoding().contentIfChanged(
            MusicOneUiState(currentTrack = first, queue = listOf(first, other), radioActive = true))))
        assertEquals(2, json.getJSONArray("queue").length())
        assertTrue(json.getBoolean("radio"))
    }
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "专辑", 180_000,
        0, 0, "", "https://expired.example/audio.mp3", lyrics = listOf(TimedLyric(1_000, "歌词")))
}
