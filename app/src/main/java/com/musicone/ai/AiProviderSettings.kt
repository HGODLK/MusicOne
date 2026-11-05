package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 服务配置独立处理；手动模型验证不要求服务实现模型列表接口。 */
internal class AiProviderSettings(
    private val store: AiConfigurationStore,
    private val client: AiProviderClient,
    private val scope: CoroutineScope,
    private val current: () -> AiAssistantUiState,
    private val update: (AiProvider, (AiProviderUiState) -> AiProviderUiState) -> Unit,
    private val changed: () -> Unit,
) {
    fun state(provider: AiProvider): AiProviderUiState {
        val key = runCatching { store.key(provider) }
        val options = store.configuration(provider)
        return AiProviderUiState(provider.name.lowercase(), provider.label, key.getOrDefault(""), store.models(provider).map { it.id },
            store.selectedModel(provider), store.isVerified(provider) && (provider == AiProvider.OTHER || key.getOrDefault("").isNotBlank()),
            error = key.exceptionOrNull()?.message, baseUrl = store.baseUrl(provider), modelInput = store.selectedModel(provider),
            contextTokens = options.contextTokens.toString(), maxOutputTokens = options.maxOutputTokens.toString(),
            thinkingEnabled = options.thinkingEnabled, reasoningEffort = options.reasoningEffort)
    }

    fun editOptions(provider: AiProvider, action: AiUiAction) {
        update(provider) { old -> when (action) {
            is AiUiAction.Thinking -> old.copy(thinkingEnabled = action.enabled)
            is AiUiAction.ContextTokens -> old.copy(contextTokens = action.value)
            is AiUiAction.OutputTokens -> old.copy(maxOutputTokens = action.value)
            is AiUiAction.ReasoningEffort -> old.copy(reasoningEffort = action.value)
            else -> old
        } }
        val draft = current().providers.first { it.id == provider.name.lowercase() }
        if (draft.requestOptionsError() == null) store.saveRequestOptions(draft.applyRequestOptions(store.configuration(provider)))
        changed()
    }

    fun editKey(provider: AiProvider, value: String) {
        if (value.isBlank()) store.saveApiKey(provider, "")
        update(provider) { it.copy(key = value, verified = false, error = null) }
        changed()
    }

    fun editEndpoint(value: String) {
        update(AiProvider.OTHER) { it.copy(baseUrl = value, verified = false, error = null) }
        changed()
    }

    fun editModel(value: String) {
        update(AiProvider.OTHER) { it.copy(modelInput = value, verified = false, error = null) }
        changed()
    }

    fun verify(provider: AiProvider, fetchList: Boolean = false) {
        if (current().running) return
        val draft = current().providers.first { it.id == provider.name.lowercase() }
        if (draft.refreshing) return
        if (draft.requestOptionsError() != null) return
        val key = draft.key.trim()
        val model = draft.modelInput.trim()
        update(provider) { it.copy(refreshing = true, error = null) }
        scope.launch {
            fun sameDraft(): Boolean = current().providers.first { it.id == draft.id }.let {
                it.key.trim() == key && it.baseUrl == draft.baseUrl && it.modelInput == draft.modelInput
            }
            try {
                val endpoint = if (provider == AiProvider.OTHER) aiNormalizeBaseUrl(draft.baseUrl) else provider.baseUrl
                val manual = provider == AiProvider.OTHER && !fetchList
                val models = if (manual) {
                    client.verifyModel(endpoint, key, model)
                    (if (store.baseUrl(provider) == endpoint) store.models(provider) else emptyList()).let { known ->
                        if (known.any { it.id == model }) known else known + AiModel(model)
                    }
                } else client.fetchModels(provider, key, endpoint).models
                require(models.isNotEmpty()) { "未发现可用文字模型，可在其他服务中手动填写模型名称并验证" }
                if (!sameDraft()) { update(provider) { it.copy(refreshing = false) }; return@launch }
                withContext(Dispatchers.IO) { store.saveVerified(provider, key, models, endpoint, model.takeIf { manual }) }
                update(provider) { state(provider) }
                changed()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                update(provider) { old -> old.copy(refreshing = false, error = if (sameDraft()) error.message ?: "验证失败" else null,
                    verified = old.verified && store.isVerified(provider)) }
                changed()
            }
        }
    }
}
