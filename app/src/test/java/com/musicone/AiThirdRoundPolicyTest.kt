package com.musicone

import org.junit.Assert.*
import org.junit.Test

class AiThirdRoundPolicyTest {
    @Test fun persistentPlaylistChangesOnlyExposePreparationAndCannotRegenerate() {
        val tools = aiToolSchema(AiConfiguration()).objects().map { it.getJSONObject("function") }
        val create = tools.single { it.getString("name") == "prepare_create_playlist" }
        assertEquals(listOf("title"), create.getJSONObject("parameters").getJSONArray("required").strings())
        assertTrue(create.getJSONObject("parameters").getJSONObject("properties").has("trackRefs"))
        assertTrue(create.getJSONObject("parameters").getJSONObject("properties").has("resultHandles"))
        val delete = tools.single { it.getString("name") == "prepare_delete_playlist" }
        assertEquals(listOf("playlistId"), delete.getJSONObject("parameters").getJSONArray("required").strings())
        assertFalse(aiResponseOnlyToolAllowed("prepare_create_playlist"))
        assertFalse(aiResponseOnlyToolAllowed("prepare_delete_playlist"))
        assertFalse(tools.any { it.getString("name") in setOf("create_playlist", "delete_playlist", "confirm_plan") })
    }

    @Test fun promptPreservesIndependentCollectionAndConcisePlaybackRules() {
        val prompt = aiSystemPrompt(AiProvider.DEEPSEEK)
        assertTrue(prompt.contains("不是其他歌单的并集或总资料库"))
        assertTrue(prompt.contains("跨歌单计数必须交给playlist_stats去重"))
        assertTrue(prompt.contains("沿临时歌单原标题和曲目新建"))
        assertTrue(prompt.contains("正文只用一句“开始播放X首”"))
        assertFalse(prompt.contains("只有用户明确指定其他音源或同意跨源时"))
    }
}
