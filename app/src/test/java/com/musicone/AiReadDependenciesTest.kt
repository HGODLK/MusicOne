package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiReadDependenciesTest {
    @Test fun failedReadsBlockDependentPlansButAllowRecoveryAndNextUserTask() {
        val checkpoint = JSONObject().put("activeTurnId", "one")
        val dependencies = AiReadDependencies(checkpoint)
        val read = call("read_result", "{\"handle\":\"first\"}")
        dependencies.record(read, AiToolInputFailure("invalid_cursor", "游标错误").result())
        dependencies.check(read)
        dependencies.check(call("remote_tracks"))
        listOf("prepare_add", "prepare_create_playlist", "prepare_remove").forEach { name ->
            try { dependencies.check(call(name)); fail("依赖读取失败时不得写入") }
            catch (error: AiToolInputFailure) { assertEquals("incomplete_reads", error.code) }
        }
        dependencies.record(read, JSONObject().put("handle", "first"))
        dependencies.check(call("prepare_remove"))
        dependencies.record(read, AiToolInputFailure("invalid_cursor", "再次错误").result())
        checkpoint.put("activeTurnId", "two")
        dependencies.check(call("prepare_create_playlist"))
    }

    @Test fun successfulCompleteComparisonRecoversPreviouslyMisreadLocalHandle() {
        val checkpoint = JSONObject().put("activeTurnId", "one")
        val dependencies = AiReadDependencies(checkpoint)
        dependencies.record(call("read_result", "{\"handle\":\"first\"}"), AiToolInputFailure("invalid_cursor", "错把远端游标传入").result())
        dependencies.record(call("compare_results", "{\"leftHandles\":[\"first\"],\"rightHandles\":[\"second\"]}"), JSONObject().put("complete", true))
        dependencies.check(call("prepare_remove"))
    }

    @Test fun memberRemovalRequiresNativeConfirmationAndCannotRegenerateEffects() {
        val functions = aiToolSchema(AiConfiguration()).objects().map { it.getJSONObject("function") }
        assertTrue(functions.any { it.getString("name") == "compare_results" })
        assertTrue(functions.any { it.getString("name") == "prepare_remove" })
        assertTrue(aiResponseOnlyToolAllowed("compare_results"))
        assertFalse(aiResponseOnlyToolAllowed("prepare_remove"))
        assertFalse(functions.any { it.getString("name") == "remove_tracks" || it.getString("name") == "confirm" })
    }

    private fun call(name: String, args: String = "{}") = AiToolCall("id", name, args)
}
