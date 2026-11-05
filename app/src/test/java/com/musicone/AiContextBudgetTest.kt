package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiContextBudgetTest {
    private val config = AiConfiguration(AiProvider.OTHER, "mimo-v2.6-pro")

    @Test fun tokenEstimateDoesNotTreatChineseUtf8BytesAsTokens() {
        assertEquals(1, AiContextBudget.estimate("歌"))
        assertEquals(2, AiContextBudget.estimate("歌abcd"))
        assertEquals(1, AiContextBudget.estimate("😀"))
    }

    @Test fun actualUsageCalibratesPredictionAndModelSwitchDiscardsOldCalibration() {
        val checkpoint = JSONObject()
        AiContextBudget.record(checkpoint, config, 1000, AiUsage(promptTokens = 1500))
        assertEquals(3000L, AiContextBudget.predicted(checkpoint, config, 2000))
        assertEquals(2000L, AiContextBudget.predicted(checkpoint, config.copy(modelId = "other"), 2000))
        assertEquals(2000L, AiContextBudget.predicted(checkpoint, config.copy(baseUrl = "https://other.example/v1"), 2000))
        assertFalse(checkpoint.aiPublicCheckpoint().has("contextTokenSample"))
    }

    @Test fun fullWindowIsAvailableUntilOnlyOutputSpaceRemains() {
        assertFalse(AiContextBudget.needsCompaction(1_048_576, 900_000, config.maxOutputTokens))
        assertFalse(AiContextBudget.needsCompaction(1_048_576, 998_575, config.maxOutputTokens))
        assertTrue(AiContextBudget.needsCompaction(1_048_576, 998_576, config.maxOutputTokens))
        assertFalse(AiContextBudget.needsCompaction(null, 2_000_000, config.maxOutputTokens))
    }

    @Test fun calibratedCompactionAndProviderOverflowActuallyReleaseSpace() {
        val checkpoint = JSONObject()
        AiContextBudget.record(checkpoint, config, 500_000, AiUsage(promptTokens = 1_044_480))
        val calibrated = AiContextBudget.compactionCapacity(checkpoint, config, 1_048_576, 510_000)
        val inputBudget = calibrated - config.maxOutputTokens
        assertTrue(AiContextBudget.predicted(checkpoint, config, inputBudget) + config.maxOutputTokens <= 1_048_576)
        val forced = AiContextBudget.compactionCapacity(JSONObject(), config, 1_048_576, 100_000, rejected = true)
        assertTrue(forced - config.maxOutputTokens < 100_000)
    }

    @Test fun configuredOutputIsReservedForBothServicesAndCustomValues() {
        for (options in listOf(AiConfiguration(), config, config.copy(contextTokens = 900_000, maxOutputTokens = 250_000))) {
            val available = options.contextTokens - options.maxOutputTokens
            assertFalse(AiContextBudget.needsCompaction(options.contextTokens, available.toLong() - 1, options.maxOutputTokens))
            assertTrue(AiContextBudget.needsCompaction(options.contextTokens, available.toLong(), options.maxOutputTokens))
            val checkpoint = JSONObject()
            AiContextBudget.record(checkpoint, options, 100_000, AiUsage(promptTokens = 200_000))
            val capacity = AiContextBudget.compactionCapacity(checkpoint, options, options.contextTokens, 100_000)
            assertEquals(available / 2, capacity - options.maxOutputTokens)
        }
    }
}
