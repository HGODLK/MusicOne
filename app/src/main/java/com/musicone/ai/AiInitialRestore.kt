package com.musicone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class AiInitialRestore(val saved: JSONObject, val providers: List<AiProviderUiState>,
    val configuration: AiConfiguration, val model: AiModel?, val messages: List<AiChatMessageUi>,
    val conversationId: String, val conversations: List<AiConversationUi>)

/** 解密、模型资料和消息解码都在后台完成，会话身份与消息在首个界面快照中一起交付。 */
internal suspend fun aiRestoreInitial(session: AiSessionStore, configuration: AiConfigurationStore,
    providerState: (AiProvider) -> AiProviderUiState): AiInitialRestore = withContext(Dispatchers.IO) {
    // 控制器每个进程只初始化一次；冷启动新建对话，进程内重新进入仍沿用当前对话。
    session.newConversation()
    val saved = session.read()
    val selected = configuration.load()
    AiInitialRestore(saved, AiProvider.entries.map(providerState), selected,
        configuration.models(selected.provider).firstOrNull { it.id == selected.modelId },
        saved.optJSONArray("messages")?.objects()?.map { it.toAiMessage() }.orEmpty(),
        session.currentConversationId,
        session.listConversations().map { AiConversationUi(it.id, it.title, it.updatedAt) })
}
