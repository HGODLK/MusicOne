package com.musicone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class AiConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("musicone_ai_configuration", Context.MODE_PRIVATE)

    init { migrateLegacyProvider() }

    private fun migrateLegacyProvider() {
        val old = preferences.all
        val migrated = aiMigrateProviderSettings(old)
        if (migrated.isEmpty()) return
        val editor = preferences.edit()
        migrated.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        } }
        check(editor.commit()) { "无法迁移 AI 服务配置" }
    }

    fun baseUrl(provider: AiProvider): String = if (provider == AiProvider.DEEPSEEK) provider.baseUrl
        else preferences.getString("base_url_OTHER", "").orEmpty()
    fun webSearchAvailable(): Boolean = isVerified(AiProvider.DEEPSEEK) && runCatching { key(AiProvider.DEEPSEEK).isNotBlank() }.getOrDefault(false)

    fun load(): AiConfiguration {
        val provider = runCatching { AiProvider.valueOf(preferences.getString("provider", null).orEmpty()) }.getOrDefault(AiProvider.DEEPSEEK)
        return configuration(provider)
    }

    fun save(configuration: AiConfiguration) {
        saveRequestOptions(configuration)
        preferences.edit().putString("provider", configuration.provider.name)
            .putString("model_${configuration.provider.name}", configuration.modelId)
            .apply { if (configuration.modelId.isNotBlank()) putBoolean("reselect_${configuration.provider.name}", false) }.apply()
    }

    fun saveRequestOptions(configuration: AiConfiguration) {
        require(aiRequestOptionsError(configuration.contextTokens.toString(), configuration.maxOutputTokens.toString(),
            configuration.thinkingEnabled, configuration.reasoningEffort) == null)
        preferences.edit().putInt("context_${configuration.provider.name}", configuration.contextTokens)
            .putInt("output_${configuration.provider.name}", configuration.maxOutputTokens)
            .putBoolean("thinking_${configuration.provider.name}", configuration.thinkingEnabled)
            .putString("effort_${configuration.provider.name}", configuration.reasoningEffort).apply()
    }

    fun selectedProvider(): AiProvider = load().provider
    fun selectProvider(provider: AiProvider) { preferences.edit().putString("provider", provider.name).apply() }
    fun configuration(provider: AiProvider): AiConfiguration = AiConfiguration(provider, selectedModel(provider),
        webSearchEnabled = webSearchAvailable(), thinkingEnabled = preferences.getBoolean("thinking_${provider.name}", false),
        personality = personality(), personalityEnabled = personalityEnabled(), baseUrl = baseUrl(provider),
        contextTokens = if (provider == AiProvider.DEEPSEEK) aiDefaultContextTokens(provider) else preferences.getInt("context_OTHER", aiDefaultContextTokens(provider)),
        maxOutputTokens = if (provider == AiProvider.DEEPSEEK) aiDefaultOutputTokens(provider) else preferences.getInt("output_OTHER", aiDefaultOutputTokens(provider)),
        reasoningEffort = preferences.getString("effort_${provider.name}", "low").orEmpty())
    fun personality(): String = preferences.getString("personality", "").orEmpty()
    fun personalityEnabled(): Boolean = preferences.getBoolean("personality_enabled", false)
    fun savePersonality(text: String, enabled: Boolean) {
        preferences.edit().putString("personality", text).putBoolean("personality_enabled", enabled).apply()
    }
    fun key(provider: AiProvider): String = apiKey(provider)
    fun requiresModelSelection(provider: AiProvider): Boolean = preferences.getBoolean("reselect_${provider.name}", false)
    fun selectModel(provider: AiProvider, id: String) = saveSelectedModel(provider, id)
    fun isVerified(provider: AiProvider): Boolean = preferences.getBoolean("verified_${provider.name}", false)
    fun invalidate(provider: AiProvider) { preferences.edit().putBoolean("verified_${provider.name}", false).apply() }
    fun models(provider: AiProvider): List<AiModel> {
        val json = runCatching { JSONArray(preferences.getString("models_${provider.name}", "[]")) }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { index -> json.optJSONObject(index)?.let {
            AiModel(it.optString("id"), it.optString("name"),
                AiProviderProtocol.contextTokens(it.optString("id"), it.optInt("context_window")),
                it.optBoolean("supportsThinking"), it.optBoolean("supportsLowThinking",
                    it.optString("id") in setOf("deepseek-flash", "deepseek-v4-pro")))
        } }
    }
    fun saveVerified(provider: AiProvider, key: String, models: List<AiModel>, baseUrl: String = baseUrl(provider), modelId: String? = null) {
        val endpoint = if (provider == AiProvider.OTHER) aiNormalizeBaseUrl(baseUrl) else provider.baseUrl
        saveApiKey(provider, key)
        val array = JSONArray()
        models.forEach { model -> array.put(JSONObject().put("id", model.id).put("name", model.name)
            .put("supportsThinking", model.supportsThinking).put("supportsLowThinking", model.supportsLowThinking)
            .apply { model.contextTokens?.let { put("context_window", it) } }) }
        val previous = modelId ?: selectedModel(provider)
        val missing = (previous.isNotBlank() && models.none { it.id == previous }) || (modelId == null && requiresModelSelection(provider))
        val selected = if (missing) "" else if (previous.isBlank()) models.firstOrNull()?.id.orEmpty() else previous
        preferences.edit().putString("models_${provider.name}", array.toString()).putString("model_${provider.name}", selected)
            .putBoolean("reselect_${provider.name}", missing).putBoolean("verified_${provider.name}", true)
            .apply { if (provider == AiProvider.OTHER) putString("base_url_OTHER", endpoint) }.apply()
    }

    fun selectedModel(provider: AiProvider): String = preferences.getString("model_${provider.name}", "").orEmpty()
    fun saveSelectedModel(provider: AiProvider, modelId: String) {
        preferences.edit().putString("model_${provider.name}", modelId)
            .apply { if (modelId.isNotBlank()) putBoolean("reselect_${provider.name}", false) }.apply()
    }
    fun hasApiKey(provider: AiProvider): Boolean = preferences.contains("key_${provider.name}")

    fun saveApiKey(provider: AiProvider, apiKey: String) {
        if (apiKey.isBlank()) {
            preferences.edit().remove("key_${provider.name}").putBoolean("verified_${provider.name}", false).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("key_${provider.name}", encoded).putBoolean("verified_${provider.name}", false).commit()) { "无法保存 API Key" }
    }

    internal fun apiKey(provider: AiProvider): String {
        val encoded = preferences.getString("key_${provider.name}", null) ?: return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw AiProviderException(AiFailureKind.CONFIGURATION, "密钥无法解密，请重新输入 API Key")
        }
    }

    @Synchronized private fun encryptionKey(): SecretKey {
        val alias = "musicone_ai_provider_keys_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
