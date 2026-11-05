package com.musicone

/** 连续助手输出和原生收据属于同一回答，折叠入口间距与消息内一致。 */
internal fun aiChatMessageGap(previous: AiChatMessageUi?, current: AiChatMessageUi): Int = when {
    previous == null -> 0
    previous.role == "assistant" && current.role == "assistant" -> 0
    else -> 24
}
