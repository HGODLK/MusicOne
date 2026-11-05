package com.musicone

import org.json.JSONObject

/** 用户消息是会话保存依据，恢复提示和原生回执不能算作用户发送。 */
internal fun aiFirstUserQuestion(messages: List<AiChatMessageUi>, history: List<JSONObject>): String? =
    messages.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text
        ?: history.firstOrNull { it.isAiUserSend() }?.optString("content")

internal fun aiLastUserSentAt(messages: List<AiChatMessageUi>, history: List<JSONObject>): Long = maxOf(
    messages.filter { it.role == "user" }.maxOfOrNull { it.sentAt } ?: 0L,
    history.filter { it.isAiUserSend() }.maxOfOrNull { it.optLong("musiconeSentAt") } ?: 0L,
)

private fun JSONObject.isAiUserSend(): Boolean = optString("role") == "user" &&
    optString("content").isNotBlank() && (has("musiconeTurnId") ||
        !optString("content").let { text -> listOf("MusicOne从任务断点恢复", "对话从断点恢复", "原生操作",
            "MusicOne原生确认结果", "MusicOne保存的音乐偏好资料", "MusicOne自动压缩的会话记忆").any(text::startsWith) })
