package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResultItemsTest {
    private val handle = "b0665fcb-90c7-4760-91d3-dca1f466a904"

    private fun track(id: String, source: MusicSource = MusicSource.QQ, playable: Boolean = true, reason: String? = null,
        badge: MusicAccessBadge? = null, trial: Boolean = false) =
        MusicTrack(id, source, "歌$id", "艺人", "专辑", 1000, 1, 2, "♪", "",
            playable = playable, unavailableReason = reason, accessBadge = badge, trialAvailable = trial)

    @Test fun uniformFacetsMoveToDefaultsAndItemsKeepOnlyCoreFields() {
        val (items, defaults) = aiResultItems(handle, listOf(0 to track("a"), 1 to track("b")))
        assertEquals("com.musicone.source.qq", defaults.getString("sourceId"))
        assertTrue(defaults.getBoolean("playable"))
        // 整页都未声明的权益字段不输出
        assertFalse(defaults.has("accessBadge"))
        assertFalse(defaults.has("unavailableReason"))
        val first = items.getJSONObject(0)
        assertEquals(setOf("trackRef", "title", "artists", "album"), first.keys().asSequence().toSet())
        assertEquals("b0665fcb:0", first.getString("trackRef"))
    }

    @Test fun mixedFacetsStayOnItemsAndNullsAreOmitted() {
        val (items, defaults) = aiResultItems(handle, listOf(
            0 to track("a", badge = MusicAccessBadge.VIP),
            1 to track("b", source = MusicSource.LOCAL, playable = false, reason = "文件丢失"),
        ))
        assertFalse(defaults.has("sourceId"))
        val vip = items.getJSONObject(0)
        assertEquals("VIP", vip.getString("accessBadge"))
        assertTrue(vip.getBoolean("playable"))
        assertFalse(vip.has("unavailableReason"))
        val broken = items.getJSONObject(1)
        assertFalse(broken.getBoolean("playable"))
        assertEquals("文件丢失", broken.getString("unavailableReason"))
        assertFalse(broken.has("accessBadge"))
    }

    @Test fun parsesShortAndLegacyRefs() {
        assertEquals("b0665fcb" to 3, aiParseTrackRef("b0665fcb:3"))
        assertEquals(handle to 12, aiParseTrackRef("$handle:12"))
        assertNull(aiParseTrackRef("b0665fcb"))
        assertNull(aiParseTrackRef(":3"))
        assertNull(aiParseTrackRef("b0665fcb:-1"))
    }
}
