package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiRichContentTest {
    @Test fun threePlusThreeTracksPublishOnePlaylistOnlyAfterFinishAndCheckpointReload() {
        val checkpoint = JSONObject().put("activeTurnId", "turn").put("displayRecommendationTitle", "夜间音乐")
        val cards = AiDeferredCards(checkpoint)
        val published = mutableListOf<AiChatMessageUi>()
        cards.stage((1..3).map(::track), "第一批")
        cards.stage((4..6).map(::track), "第二批")
        assertTrue(published.isEmpty())
        assertEquals(6, checkpoint.getJSONObject("pendingCards").getJSONArray("tracks").length())
        val restored = JSONObject(checkpoint.toString())
        val recoveredCards = AiDeferredCards(restored)
        published += requireNotNull(recoveredCards.finish())
        assertEquals(1, published.size)
        val message = published.single()
        assertTrue(message.tracks.isEmpty())
        assertEquals("夜间音乐", message.playlist!!.title)
        assertEquals((1..6).map { "track-$it" }, message.playlist.tracks.map { it.id })
        assertEquals(6, message.playlist.count)
        assertNull(recoveredCards.finish())
        assertFalse(restored.has("pendingCards"))
    }

    @Test fun duplicateTracksAcrossCallsAppearOnceInOrder() {
        val checkpoint = JSONObject().put("activeTurnId", "dedupe")
        val cards = AiDeferredCards(checkpoint)
        cards.stage(listOf(track(1), track(2), track(1)), "两首")
        cards.stage(listOf(track(2), track(3)), "补充")
        val result = requireNotNull(cards.finish())
        assertNull(result.playlist)
        assertEquals(listOf("track-1", "track-2", "track-3"), result.tracks.map { it.id })
    }

    @Test fun latestRealPlaylistReplacesEarlierCardsAndKeepsItsCompleteIdentity() {
        val checkpoint = JSONObject().put("activeTurnId", "real")
        val cards = AiDeferredCards(checkpoint)
        cards.stage(listOf(track(1), track(2)), "先前单曲")
        cards.stage(playlist(listOf(track(3), track(4))))
        val latest = playlist(listOf(track(5))).copy(id = "last-real", title = "最后歌单", count = 12, remoteKey = "last-remote")
        cards.stage(latest)
        val result = requireNotNull(cards.finish())
        assertEquals(latest, result.playlist)
        assertTrue(result.tracks.isEmpty())
    }

    @Test fun singlesAfterRealPlaylistCreateTemporaryViewRatherThanReuseRemoteIdentity() {
        val checkpoint = JSONObject().put("activeTurnId", "mixed")
        val cards = AiDeferredCards(checkpoint)
        cards.stage(playlist(listOf(track(1), track(2))))
        cards.stage(listOf(track(2), track(3)), "追加推荐")
        val result = requireNotNull(cards.finish())
        val combined = requireNotNull(result.playlist)
        assertEquals(MusicSource.LOCAL, combined.source)
        assertEquals("ai-turn-mixed", combined.id)
        assertEquals("", combined.remoteKey)
        assertNull(combined.sourceAccountId)
        assertEquals(listOf("track-1", "track-2", "track-3"), combined.tracks.map { it.id })
        assertTrue(result.tracks.isEmpty())
    }

    @Test fun publicCheckpointExcludesPrivatePendingCardsWithoutMutatingRecoveryData() {
        val checkpoint = JSONObject().put("activeTurnId", "private").put("notes", "允许提供模型的摘要")
            .put("cursor", "public-cursor")
        AiDeferredCards(checkpoint).stage(playlist(listOf(track(1).copy(providerData = mapOf("sourcePayload" to "private-payload")))))
        val publicView = checkpoint.aiPublicCheckpoint()
        assertFalse(publicView.has("pendingCards"))
        assertFalse(publicView.toString().contains("private-payload"))
        assertEquals("允许提供模型的摘要", publicView.getString("notes"))
        assertEquals("public-cursor", publicView.getString("cursor"))
        assertTrue(checkpoint.has("pendingCards"))
        assertTrue(checkpoint.toString().contains("private-payload"))
        publicView.put("notes", "模型摘要变更")
        assertEquals("允许提供模型的摘要", checkpoint.getString("notes"))
    }

    @Test fun publicCheckpointDoesNotExposeLocalUsageDiagnostics() {
        val checkpoint = JSONObject().put("goal", "读取歌单").put("actualUsage", JSONObject().put("totalTokens", 99))
        assertFalse(checkpoint.aiPublicCheckpoint().has("actualUsage"))
        assertTrue(checkpoint.has("actualUsage"))
    }

    @Test fun previousTurnPendingCardsCannotLeakIntoNewTurn() {
        val checkpoint = JSONObject().put("activeTurnId", "old")
        AiDeferredCards(checkpoint).stage(listOf(track(1)), "旧推荐")
        checkpoint.put("activeTurnId", "new")
        val restored = AiDeferredCards(JSONObject(checkpoint.toString()))
        assertNull(restored.finish())
        restored.stage(listOf(track(2)), "新推荐")
        assertEquals(listOf("track-2"), requireNotNull(restored.finish()).tracks.map { it.id })
    }

    @Test fun playlistAndGroupedReceiptSurviveMessageSerializationTogether() {
        val receipt = AiReceiptUi("已修改 1 首；跳过 2 项，失败 0 项。", listOf(
            AiChangeGroupUi("歌曲一", listOf(AiFieldChangeUi("歌名", "旧歌名", "新歌名"), AiFieldChangeUi("艺人", "旧艺人", "新艺人"))),
            AiChangeGroupUi("晚间歌单", listOf(AiFieldChangeUi("添加歌曲", "未加入", "歌曲二"))),
        ))
        val original = AiChatMessageUi("rich", "assistant", "处理完成", playlist = playlist(listOf(track(1), track(2))), receipt = receipt,
            isNativeReceipt = true, turnId = "turn", result = receipt.summary)
        val restored = JSONObject(original.toAiJson().toString()).toAiMessage()
        assertEquals(original, restored)
        assertEquals("真实远端歌单", restored.playlist!!.title)
        assertEquals("account", restored.playlist.sourceAccountId)
        assertEquals("remote-list", restored.playlist.remoteKey)
        assertEquals(2, restored.receipt!!.groups.size)
    }

    @Test fun receiptUsesActualChangesAndRealCountsInsteadOfPlannedMetadata() {
        fun field(name: String, before: String, after: Any) = JSONObject().put("field", name).put("oldDisplay", before).put("new", after)
        val data = JSONObject().put("trackChanges", JSONArray().put(JSONObject().put("title", "未执行的计划")))
            .put("trackActual", JSONArray().put(JSONObject().put("title", "实际歌曲").put("fields", JSONArray()
                .put(field("title", "旧标题", "新标题")).put(field("album", "旧专辑", JSONObject.NULL)))))
            .put("changes", JSONArray().put(JSONObject().put("oldTitle", "旧歌单").put("newTitle", "新歌单")
                .put("addCount", 7).put("removeCount", 3).put("duplicateCount", 2)
                .put("tracks", JSONArray().put(JSONObject().put("title", "新增歌曲")))
                .put("remove", JSONArray().put(JSONObject().put("title", "撤销歌曲")))))
            .put("skippedCount", 4)
        val groups = aiChangeGroups(data)
        assertEquals(listOf("实际歌曲", "旧歌单"), groups.map { it.title })
        assertEquals(AiFieldChangeUi("歌名", "旧标题", "新标题"), groups.first().fields.first())
        assertEquals("恢复原始资料", groups.first().fields.last().after)
        assertEquals(listOf("歌单名称", "添加歌曲", "撤销添加"), groups.last().fields.map { it.label })
        assertEquals("已修改 1 首，已重命名 1 个歌单，已添加 7 首，已撤销添加 3 首；跳过 6 项，失败 0 项。", aiChangeSummary(data, true))
        assertFalse(aiChangeSummary(data, false).contains("失败 0"))
    }

    private fun track(index: Int) = MusicTrack("track-$index", MusicSource.LOCAL, "歌曲$index", "艺人", "专辑", 180000,
        0, 0, "", "", localUri = "content://music/$index")

    private fun playlist(tracks: List<MusicTrack>) = MusicPlaylist("real-playlist", MusicSource.NETEASE, "真实远端歌单", "创建者",
        "真实简介", tracks.size, 1, 2, "封面", tracks, artworkUrl = "https://example.test/cover.jpg", isOwned = true,
        providerData = mapOf("opaque" to "private-reference"), sourceAccountId = "account", remoteKey = "remote-list")
}
