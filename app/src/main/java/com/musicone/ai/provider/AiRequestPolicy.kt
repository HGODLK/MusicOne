package com.musicone

/** 用户配置的容量用于真实请求和上下文预算，不根据模型名称擅自缩减。 */
internal fun aiDefaultContextTokens(provider: AiProvider): Int =
    if (provider == AiProvider.DEEPSEEK) 1_000_000 else 200_000

internal fun aiDefaultOutputTokens(provider: AiProvider): Int =
    if (provider == AiProvider.DEEPSEEK) 384_000 else 50_000

internal fun aiRequestOptionsError(context: String, output: String, thinking: Boolean, effort: String): String? {
    val capacity = context.toIntOrNull()?.takeIf { it > 0 } ?: return "上下文容量必须为正整数"
    val maximum = output.toIntOrNull()?.takeIf { it > 0 } ?: return "输出上限必须为正整数"
    if (maximum >= capacity) return "输出上限必须小于上下文容量，以保留输入空间"
    if (thinking && effort.isBlank()) return "请填写思考强度"
    return null
}

internal fun AiProviderUiState.requestOptionsError(): String? =
    aiRequestOptionsError(contextTokens, maxOutputTokens, thinkingEnabled, reasoningEffort)

internal fun AiProviderUiState.applyRequestOptions(config: AiConfiguration): AiConfiguration {
    require(requestOptionsError() == null) { requestOptionsError().orEmpty() }
    return config.copy(contextTokens = contextTokens.toInt(), maxOutputTokens = maxOutputTokens.toInt(),
        thinkingEnabled = thinkingEnabled, reasoningEffort = reasoningEffort.trim())
}
