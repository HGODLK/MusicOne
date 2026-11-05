package com.musicone

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class AiHistoryPaging(private val scope: CoroutineScope, private val session: AiSessionStore,
    private val state: MutableStateFlow<AiAssistantUiState>) {
    private var job: Job? = null
    fun cancel() { job?.cancel(); state.update { it.copy(loadingEarlierMessages = false) } }
    fun load() {
        if (job?.isActive == true || !state.value.hasEarlierMessages) return
        val id = state.value.currentConversationId
        val first = state.value.messages.firstOrNull()?.id ?: return
        state.update { it.copy(loadingEarlierMessages = true) }
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { session.readEarlierMessages(first, id) }
                ensureActive()
                state.update { current -> if (current.currentConversationId != id) current else current.copy(
                    messages = page.messages.filter { row -> current.messages.none { it.id == row.id } } + current.messages,
                    hasEarlierMessages = page.hasEarlier, loadingEarlierMessages = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { if (it.currentConversationId == id) it.copy(loadingEarlierMessages = false,
                error = "无法加载更早消息，请重试") else it } }
        }
    }
}
