package com.musicone

internal data class AiProviderUiState(
    val id: String, val name: String, val key: String = "", val models: List<String> = emptyList(),
    val selectedModel: String = "", val verified: Boolean = false, val refreshing: Boolean = false,
    val error: String? = null, val baseUrl: String = "", val modelInput: String = "",
    val contextTokens: String = "200000", val maxOutputTokens: String = "50000",
    val thinkingEnabled: Boolean = false, val reasoningEffort: String = "low",
)

internal data class AiPlaylistReferenceUi(val id: String, val title: String)
internal data class AiConversationUi(val id: String, val title: String, val updatedAt: Long, val snippet: String = "")
internal data class AiFieldChangeUi(val label: String, val before: String, val after: String)
internal data class AiChangeGroupUi(val title: String, val fields: List<AiFieldChangeUi>)
internal data class AiReceiptUi(val summary: String, val groups: List<AiChangeGroupUi>)
internal data class AiChatMessageUi(
    val id: String, val role: String, val text: String, val tracks: List<MusicTrack> = emptyList(),
    val citations: List<String> = emptyList(), val result: String? = null, val usage: String? = null,
    val reasoning: String = "",
    val turnId: String = id, val reasoningElapsedMs: Long? = null, val isThinking: Boolean = false,
    val progressSteps: List<String> = emptyList(),
    val isNativeReceipt: Boolean = false,
    val playlist: MusicPlaylist? = null,
    val receipt: AiReceiptUi? = null,
    val sentAt: Long = 0L,
)
internal data class AiPlanUi(val id: String, val title: String, val details: List<String>,
    val groups: List<AiChangeGroupUi> = emptyList(), val summary: String? = null)

internal data class AiAssistantUiState(
    val configVisible: Boolean = true,
    val providerId: String = "deepseek",
    val providers: List<AiProviderUiState> = emptyList(),
    val messages: List<AiChatMessageUi> = emptyList(),
    val input: String = "", val running: Boolean = false, val progress: String? = null,
    val references: List<AiPlaylistReferenceUi> = emptyList(),
    val availablePlaylists: List<AiPlaylistReferenceUi> = emptyList(),
    val webEnabled: Boolean = false, val thinkingEnabled: Boolean = false,
    val thinkingSupported: Boolean = false, val pendingPlan: AiPlanUi? = null,
    val error: String? = null, val canUndo: Boolean = false,
    val canContinue: Boolean = false, val canRetry: Boolean = false,
    val conversations: List<AiConversationUi> = emptyList(), val currentConversationId: String = "",
    val memorySummary: String? = null,
    val personality: String = "", val personalityEnabled: Boolean = false,
    val thinkingNotice: String? = null,
    val initialized: Boolean = false,
    val historyVisible: Boolean = false, val historyQuery: String = "",
    val historyResults: List<AiConversationUi> = emptyList(), val historySearching: Boolean = false,
    val openedPlaylist: MusicPlaylist? = null,
    val hasEarlierMessages: Boolean = false, val loadingEarlierMessages: Boolean = false,
)

internal sealed interface AiUiAction {
    data object Open : AiUiAction
    data class SelectProvider(val id: String) : AiUiAction
    data class EditKey(val value: String) : AiUiAction
    data class EditBaseUrl(val value: String) : AiUiAction
    data class EditModel(val value: String) : AiUiAction
    data object FetchModels : AiUiAction
    data object RefreshModels : AiUiAction
    data class SelectModel(val id: String) : AiUiAction
    data object ShowConfig : AiUiAction
    data object CloseConfig : AiUiAction
    data object SaveConfiguration : AiUiAction
    data object NewConversation : AiUiAction
    data object ShowHistory : AiUiAction
    data object CloseHistory : AiUiAction
    data class SearchHistory(val query: String) : AiUiAction
    data class OpenPlaylist(val playlist: MusicPlaylist) : AiUiAction
    data object ClosePlaylist : AiUiAction
    data object LoadEarlierMessages : AiUiAction
    data class SelectConversation(val id: String) : AiUiAction
    data class DeleteConversations(val ids: Set<String>) : AiUiAction
    data object ClearMemory : AiUiAction
    data class EditPersonality(val value: String) : AiUiAction
    data class PersonalityEnabled(val enabled: Boolean) : AiUiAction
    data object RestoreDefaultPersonality : AiUiAction
    data object Retry : AiUiAction
    data object ContinueTask : AiUiAction
    data class Input(val value: String) : AiUiAction
    data object Send : AiUiAction
    data object Stop : AiUiAction
    data class Web(val enabled: Boolean) : AiUiAction
    data class Thinking(val enabled: Boolean) : AiUiAction
    data class ContextTokens(val value: String) : AiUiAction
    data class OutputTokens(val value: String) : AiUiAction
    data class ReasoningEffort(val value: String) : AiUiAction
    data class AddReference(val id: String) : AiUiAction
    data class RemoveReference(val id: String) : AiUiAction
    data object ConfirmPlan : AiUiAction
    data object CancelPlan : AiUiAction
    data object Undo : AiUiAction
}
