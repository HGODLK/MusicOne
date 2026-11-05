package com.musicone

/** 旧版历史未记录轮次标识，同一用户轮内的连续助手输出合为一条。 */
internal fun aiVisibleMessages(messages: List<AiChatMessageUi>): List<AiChatMessageUi> {
    val visible = mutableListOf<AiChatMessageUi>()
    messages.forEach { message ->
        if (message.text.isBlank() && message.reasoning.isBlank() && !message.isThinking &&
            message.tracks.isEmpty() && message.playlist == null && message.receipt == null &&
            message.result.isNullOrBlank() && message.progressSteps.isEmpty()) return@forEach
        val previous = visible.lastOrNull()
        if (message.role == "assistant" && message.turnId.isBlank() && !message.isNativeReceipt &&
            previous?.role == "assistant" && previous.turnId.isBlank() && !previous.isNativeReceipt) {
            visible[visible.lastIndex] = previous.copy(
                text = listOf(previous.text, message.text).filter(String::isNotBlank).joinToString("\n\n"),
                reasoning = listOf(previous.reasoning, message.reasoning).filter(String::isNotBlank).joinToString("\n\n"),
                tracks = (previous.tracks + message.tracks).distinctBy { it.source to it.id },
                citations = (previous.citations + message.citations).distinct(),
                progressSteps = previous.progressSteps + message.progressSteps,
                result = listOfNotNull(previous.result, message.result).filter(String::isNotBlank).distinct()
                    .joinToString("\n\n").takeIf(String::isNotBlank),
                usage = message.usage ?: previous.usage,
                reasoningElapsedMs = listOfNotNull(previous.reasoningElapsedMs, message.reasoningElapsedMs)
                    .takeIf { it.isNotEmpty() }?.sum(),
                isThinking = previous.isThinking || message.isThinking,
                playlist = message.playlist ?: previous.playlist,
                receipt = message.receipt ?: previous.receipt,
            )
        } else visible += message
    }
    return visible
}

/** 已显示前缀只核对对象身份，流式更新只重新投影改变的尾部。 */
internal class AiVisibleMessagesCache {
    private var source: List<AiChatMessageUi> = emptyList()
    private var visible: List<AiChatMessageUi> = emptyList()
    private var prefixSizes = emptyList<Int>()

    fun update(messages: List<AiChatMessageUi>): List<AiChatMessageUi> {
        if (messages === source) return visible
        var common = 0
        while (common < source.size && common < messages.size && source[common] === messages[common]) common++
        // 旧格式可能跨消息合并；回退到最后一个未合并边界后仅重算尾部。
        while (common > 0 && common < source.size &&
            source[common].role == "assistant" && source[common].turnId.isBlank() &&
            source[common - 1].role == "assistant" && source[common - 1].turnId.isBlank()) common--
        val result = visible.take(if (common == 0) 0 else prefixSizes[common - 1]).toMutableList()
        val sizes = prefixSizes.take(common).toMutableList()
        for (index in common until messages.size) {
            val tail = aiVisibleMessages(listOfNotNull(result.lastOrNull(), messages[index]))
            if (result.isNotEmpty()) result.removeAt(result.lastIndex)
            result.addAll(tail)
            sizes += result.size
        }
        source = messages
        visible = result
        prefixSizes = sizes
        return visible
    }
}
