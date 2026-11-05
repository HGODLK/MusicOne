package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiLibraryContractTest {
    @Test fun regenerationAllowsReadingAndCardsButNeverEffects() {
        listOf("list_playlists", "search_tracks", "show_tracks", "read_result", "read_task").forEach {
            assertTrue(it, aiResponseOnlyToolAllowed(it))
        }
        listOf("play_tracks", "prepare_edit_tracks", "prepare_add", "prepare_rename", "prepare_undo", "save_task", "unknown_future_write").forEach {
            assertFalse(it, aiResponseOnlyToolAllowed(it))
        }
    }

    @Test fun undoPreservesLaterEditsEvenIfTextReturnedToSameValue() {
        assertTrue(aiMetadataUndoMatches(4, 4, "助手标题", "助手标题"))
        assertFalse(aiMetadataUndoMatches(6, 4, "助手标题", "助手标题"))
        assertFalse(aiMetadataUndoMatches(4, 4, "用户标题", "助手标题"))
        assertTrue(aiMetadataUndoMatches(4, 4, null, null))
    }

    @Test fun nativeMetadataPlanShowsEveryFieldDifference() {
        val data = JSONObject().put("trackChanges", JSONArray().put(JSONObject().put("title", "旧歌名")
            .put("fields", JSONArray().put(JSONObject().put("field", "title").put("oldDisplay", "旧歌名").put("new", "新歌名"))
                .put(JSONObject().put("field", "artists").put("oldDisplay", "旧艺人").put("new", "新艺人")))))
        val plan = AiLibraryPlan("plan", "edit_tracks", emptyList(), "pending", aiMetadataDetails(data)).toUiPlan()
        assertTrue(plan.toString().contains("旧歌名 → 新歌名"))
        assertTrue(plan.toString().contains("旧艺人 → 新艺人"))
    }

    @Test fun metadataToolOffersOnlyExistingTextFieldsAndNoDirectConfirmation() {
        val functions = aiToolSchema(AiConfiguration()).objects().map { it.getJSONObject("function") }
        val tool = functions.single { it.getString("name") == "prepare_edit_tracks" }
        val properties = tool.getJSONObject("parameters").getJSONObject("properties").getJSONObject("changes")
            .getJSONObject("items").getJSONObject("properties")
        assertEquals(setOf("trackRef", "title", "artists", "album"), properties.keys().asSequence().toSet())
        assertFalse(functions.any { it.getString("name") in setOf("confirm", "confirm_plan", "delete_playlist", "remote_write") })
    }
}
