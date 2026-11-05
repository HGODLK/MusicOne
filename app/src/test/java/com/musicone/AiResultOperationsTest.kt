package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiResultOperationsTest {
    @Test fun intersectionDifferenceAndUnionUseSourceAndIdAndKeepLeftOrder() {
        val a = track("a")
        val b = track("b")
        val sameName = track("c").copy(title = a.title)
        val otherSource = a.copy(source = MusicSource.QQ)
        assertEquals(listOf(b), aiTrackSet("intersection", listOf(a, b), listOf(b, sameName, otherSource)))
        assertEquals(listOf(a), aiTrackSet("difference", listOf(a, b), listOf(b, sameName, otherSource)))
        assertEquals(listOf(a, b, sameName, otherSource), aiTrackSet("union", listOf(a, b, a), listOf(b, sameName, otherSource)))
    }

    @Test fun fourThousandTrackDifferenceHasNoPageOrSelectionCap() {
        val left = (0 until 4000).map { track("$it") }
        val right = (0 until 4000 step 2).map { track("$it") }
        val result = aiTrackSet("difference", left, right)
        assertEquals((1 until 4000 step 2).map { "$it" }, result.map { it.id })
    }

    @Test fun remoteHundredAndTwentyOneTracksRequireBothPagesInEitherOrder() {
        aiRequireCompleteResults(listOf(page("", "next", 100), page("next", null, 21)))
        aiRequireCompleteResults(listOf(page("next", null, 21), page("", "next", 100)))
        aiRequireCompleteResults(listOf(JSONObject().put("complete", true).put("count", 4000)))
    }

    @Test fun missingFirstOrMiddlePageReturnsExactRecovery() {
        val missingFirst = failure { aiRequireCompleteResults(listOf(page("next", null, 21))) }
        assertFalse(missingFirst.result().getBoolean("retryable"))
        assertEquals("remote_tracks", missingFirst.recovery!!.getString("tool"))
        assertFalse(missingFirst.recovery.getJSONObject("arguments").has("cursor"))
        val missingMiddle = failure { aiRequireCompleteResults(listOf(page("", "middle", 100), page("last", null, 21))) }
        assertEquals("middle", missingMiddle.recovery!!.getJSONObject("arguments").getString("cursor"))
    }

    @Test fun loopsDuplicateInputsAndDisconnectedChainsCannotClaimComplete() {
        listOf(listOf(page("", "loop", 100), page("loop", "loop", 21)),
            listOf(page("", "next", 100), page("", "next", 100), page("next", null, 21)),
            listOf(page("", null, 100), page("unrelated", null, 21)),
            listOf(JSONObject().put("complete", false))).forEach { pages ->
            assertEquals("incomplete_collection", failure { aiRequireCompleteResults(pages) }.code)
        }
    }

    @Test fun localCursorsAreBoundToHandleAndRemoteTokensNeverBecomeOffsets() {
        assertEquals(0, aiResultOffset("h", null, 121))
        assertEquals(100, aiResultOffset("h", "result:h:100", 121))
        assertEquals(100, aiResultOffset("h", "h|100", 121))
        assertEquals(100, aiResultOffset("h", "100", 121))
        listOf("remote-uuid", "result:other:100", "other|100", "-1", "122").forEach { cursor ->
            val error = failure { aiResultOffset("h", cursor, 121) }
            assertEquals("invalid_cursor", error.code)
            assertEquals("read_result", error.recovery!!.getString("tool"))
            assertEquals("h", error.recovery.getJSONObject("arguments").getString("handle"))
        }
    }

    private fun page(input: String, next: String?, count: Int) = JSONObject().put("complete", false).put("count", count)
        .put("pagination", JSONObject().put("collectionKey", "qq:account:playlist").put("playlistRef", "ref")
            .put("inputCursor", input).put("nextCursor", next ?: JSONObject.NULL))
    private fun track(id: String) = MusicTrack(id, MusicSource.LOCAL, "相同歌名", "歌手", "专辑", 1000, 0, 0, "", "")
    private fun failure(action: () -> Any?): AiToolInputFailure {
        try { action() } catch (error: AiToolInputFailure) { return error }
        throw AssertionError("缺页或无效游标必须拒绝")
    }
}
