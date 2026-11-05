package com.musicone

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 独立偏好文件验证配置迁移与搜索资格，不使用用户的供应商密钥或网络。 */
@RunWith(AndroidJUnit4::class)
class AiProviderConfigurationTest {
    @Test fun requestOptionsRestoreSeparatelyAndDeepSeekKeepsItsFixedQuotas() = isolated { context ->
        val store = AiConfigurationStore(context)
        assertEquals(200_000, store.configuration(AiProvider.OTHER).contextTokens)
        assertEquals(50_000, store.configuration(AiProvider.OTHER).maxOutputTokens)
        store.saveRequestOptions(AiConfiguration(AiProvider.OTHER, "custom", thinkingEnabled = true,
            contextTokens = 900_000, maxOutputTokens = 256_000, reasoningEffort = "ultra"))
        store.saveRequestOptions(AiConfiguration(modelId = "deepseek-flash", thinkingEnabled = true, reasoningEffort = "max"))
        val restored = AiConfigurationStore(context)
        val other = restored.configuration(AiProvider.OTHER)
        assertEquals(900_000, other.contextTokens)
        assertEquals(256_000, other.maxOutputTokens)
        assertTrue(other.thinkingEnabled)
        assertEquals("ultra", other.reasoningEffort)
        val deepSeek = restored.configuration(AiProvider.DEEPSEEK)
        assertEquals(1_000_000, deepSeek.contextTokens)
        assertEquals(384_000, deepSeek.maxOutputTokens)
        assertEquals("max", deepSeek.reasoningEffort)
        assertTrue(deepSeek.thinkingEnabled)
        assertTrue(runCatching { restored.saveRequestOptions(other.copy(maxOutputTokens = 900_000)) }.isFailure)
        assertEquals(256_000, AiConfigurationStore(context).configuration(AiProvider.OTHER).maxOutputTokens)
    }

    @Test fun customServerNeedsNoDeepSeekOrApiKeyToSaveVerifiedModel() = isolated { context ->
        val store = AiConfigurationStore(context)
        store.saveVerified(AiProvider.OTHER, "", listOf(AiModel("local-model")), "http://192.168.1.20:8080/custom/v1/", "local-model")
        store.selectProvider(AiProvider.OTHER)
        val restored = AiConfigurationStore(context)
        val config = restored.load()
        assertEquals(AiProvider.OTHER, config.provider)
        assertEquals("http://192.168.1.20:8080/custom/v1", config.baseUrl)
        assertEquals("local-model", config.modelId)
        assertTrue(restored.isVerified(AiProvider.OTHER))
        assertFalse(config.webSearchEnabled)
        assertFalse(restored.webSearchAvailable())
    }

    @Test fun deepSeekSearchNeedsVerifiedKeyButDoesNotNeedSelectedChatModel() = isolated { context ->
        val store = AiConfigurationStore(context)
        store.saveApiKey(AiProvider.DEEPSEEK, "test-only-not-a-real-key")
        assertFalse(store.webSearchAvailable())
        store.saveVerified(AiProvider.DEEPSEEK, "test-only-not-a-real-key", listOf(AiModel("deepseek-model")))
        store.selectModel(AiProvider.DEEPSEEK, "")
        store.selectProvider(AiProvider.OTHER)
        assertTrue(store.configuration(AiProvider.OTHER).webSearchEnabled)
        assertEquals("https://api.deepseek.com", store.baseUrl(AiProvider.DEEPSEEK))
        store.saveApiKey(AiProvider.DEEPSEEK, "")
        assertFalse(store.webSearchAvailable())
    }

    @Test fun migrationPreservesEncryptedKeyAndModelWithoutVerifyingNewProtocol() = isolated { context ->
        val preferences = context.getSharedPreferences("musicone_ai_configuration", Context.MODE_PRIVATE)
        preferences.edit().putString("provider", "MIMO").putString("key_MIMO", "opaque-ciphertext")
            .putString("model_MIMO", "mimo-v2.6-pro").putString("models_MIMO", "[]").putBoolean("verified_MIMO", true).commit()
        val store = AiConfigurationStore(context)
        assertEquals(AiProvider.OTHER, store.selectedProvider())
        assertEquals("opaque-ciphertext", preferences.getString("key_OTHER", null))
        assertEquals("mimo-v2.6-pro", store.selectedModel(AiProvider.OTHER))
        assertFalse(store.isVerified(AiProvider.OTHER))
        preferences.edit().putString("base_url_OTHER", "https://custom.example/v1").commit()
        assertEquals("https://custom.example/v1", AiConfigurationStore(context).baseUrl(AiProvider.OTHER))
    }

    private fun isolated(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ai-provider-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { block(context) } finally { base.deleteSharedPreferences(name) }
    }
}
