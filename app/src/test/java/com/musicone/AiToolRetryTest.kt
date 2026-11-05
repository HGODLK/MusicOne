package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiToolRetryTest {
    @Test fun invalidCursorIsNotRetriedAndCorrectedInputCanRunImmediately() = runBlocking {
        val retries = AiToolRetry(JSONObject().put("activeTurnId", "one"))
        var attempts = 0
        val result = retries.execute("read_result", "{\"cursor\":\"remote-uuid\"}", retrying = { _, _ -> fail("坏参数不应重试") }) {
            attempts++
            aiResultOffset("h", "remote-uuid", 121)
            JSONObject()
        }
        assertEquals(1, attempts)
        assertFalse(result.getBoolean("retryable"))
        assertFalse(result.has("retriesExhausted"))
        val recovered = retries.execute("read_result", "{\"cursor\":\"100\"}") {
            attempts++
            JSONObject().put("offset", aiResultOffset("h", "100", 121))
        }
        assertEquals(100, recovered.getInt("offset"))
        assertEquals(2, attempts)
    }

    @Test fun missingRequiredJsonArgumentIsReturnedOnceWithRecoveryAllowed() = runBlocking {
        var attempts = 0
        val result = AiToolRetry(JSONObject()).execute("compare_results", "{}", retrying = { _, _ -> fail() }) {
            attempts++
            JSONObject().getJSONArray("leftHandles")
            JSONObject()
        }
        assertEquals(1, attempts)
        assertEquals("invalid_arguments", result.getString("code"))
        assertFalse(result.getBoolean("retryable"))
    }

    @Test fun exceptionsRetryThreeTimesAndChangedCallIdCannotResetBudget() = runBlocking {
        val checkpoint = JSONObject().put("activeTurnId", "one")
        val retries = AiToolRetry(checkpoint)
        var attempts = 0
        val progress = mutableListOf<Int>()
        suspend fun run(args: String) = retries.execute("read", args, retrying = { n, _ -> progress += n }) {
            attempts++; error("服务离线")
        }
        val result = run("{\"a\":1,\"b\":2}")
        assertEquals(4, attempts)
        assertEquals(listOf(1, 2, 3), progress)
        assertTrue(result.getBoolean("retriesExhausted"))
        assertTrue(result.getString("message").contains("服务离线"))
        run("{\"b\":2,\"a\":1}")
        assertEquals(4, attempts)
        checkpoint.put("activeTurnId", "two")
        run("{\"a\":1,\"b\":2}")
        assertEquals(8, attempts)
    }
    @Test fun structuredFailuresRetryAndSuccessfulSideEffectsAreReused() = runBlocking {
        val retries = AiToolRetry(JSONObject().put("activeTurnId", "one"))
        var attempts = 0
        suspend fun run() = retries.execute("play_tracks", "{}", cacheSuccess = true) {
            attempts++
            if (attempts < 3) JSONObject().put("status", "failed").put("message", "稍后再试")
            else JSONObject().put("status", "accepted")
        }
        assertEquals("accepted", run().getString("status"))
        assertEquals("accepted", run().getString("status"))
        assertEquals(3, attempts)
    }
    @Test fun cancellationAndConfirmationDoNotConsumeFailureBudget() = runBlocking {
        val retries = AiToolRetry(JSONObject())
        var attempts = 0
        try { retries.execute("read", "{}") { attempts++; throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertEquals(1, attempts)
        repeat(5) { retries.execute("prepare_add", "{}") { attempts++; JSONObject().put("status", "awaiting_confirmation") } }
        assertEquals(6, attempts)
    }
    @Test fun nestedSourceFailureStaysVisibleAndDoesNotRetrySuccessfulSources() = runBlocking {
        val retries = AiToolRetry(JSONObject())
        var success = 0; var failed = 0
        repeat(2) {
            retries.execute("source:ok", "{}", cacheSuccess = true) { success++; JSONObject().put("status", "ok") }
            retries.execute("source:bad", "{}", cacheSuccess = true) { failed++; JSONObject().put("error", "离线") }
        }
        assertEquals(1, success); assertEquals(4, failed)
    }
    @Test fun effectReceiptPreventsRepeatedSubmissionAfterUncertainFailure() = runBlocking {
        val effects = AiToolEffects(JSONObject())
        val call = AiToolCall("first", "play_tracks", "{}")
        var submissions = 0
        try { effects.stage(call, "提交播放") { submissions++; error("结果未知") }; fail() } catch (_: IllegalStateException) { }
        try { effects.stage(call.copy(id = "another"), "提交播放") { submissions++; JSONObject() }; fail() } catch (_: IllegalStateException) { }
        assertEquals(1, submissions)
    }
    @Test fun rolledBackPlanCanRetryButCommittedPlanIsNeverRecreated() = runBlocking {
        val checkpoint = JSONObject()
        val effects = AiToolEffects(checkpoint)
        val call = AiToolCall("one", "prepare_add", "{}")
        var attempts = 0
        suspend fun execute() = effects.stage(call, "生成操作明细", transactional = true) {
            attempts++
            if (attempts == 1) error("事务已回滚")
            JSONObject().put("status", "pending").put("planId", "only-plan")
        }
        val answer = AiToolRetry(checkpoint).execute(call.name, call.arguments, cacheSuccess = true) { execute() }
        assertEquals("only-plan", answer.getString("planId"))
        assertEquals("only-plan", execute().getString("planId"))
        assertEquals(2, attempts)
    }
}
