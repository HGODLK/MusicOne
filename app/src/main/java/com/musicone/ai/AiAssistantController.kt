package com.musicone

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 应用级会话独立于设置页和音源页面，关闭页面不会丢失任务。 */
internal class AiAssistantController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configuration = AiConfigurationStore(app)
    private val client = AiProviderClient(configuration)
    private val session = AiSessionStore(app)
    private val compressor = AiMemoryCompressor(client)
    private val checkpoint = JSONObject()
    private val history = mutableListOf<JSONObject>()
    private val mutable = MutableStateFlow(AiAssistantUiState())
    val state = mutable.asStateFlow()
    private var player: MusicPlaybackRuntime? = null
    private var running: Job? = null
    private var initialized = false
    private var initializing = false
    private var committing = false
    private var switching = false
    private var switchTask: Job? = null
    private val memory = AiConversationMemory(app, scope, session, compressor, history, checkpoint,
        ::currentConfiguration, { currentModel() }, ::persist)
    private var lastOperation: String? = null
    private val historyNavigation = AiHistoryNavigation(scope, session, mutable)
    private val historyPaging = AiHistoryPaging(scope, session, mutable)
    private val titles = AiConversationTitles(scope, session, client, ::refreshConversationList)
    private val deferredCards = AiDeferredCards(checkpoint)
    private val providerSettings = AiProviderSettings(configuration, client, scope, { state.value }, ::updateProvider, ::refreshProviderCapabilities)
    private val library: AiLibraryTools = AiLibraryTools(app, validateTracks = { executor.sources.validateTracks(it) })
    private val executor: AiToolExecutor = AiToolExecutor(app, library, client, checkpoint,
        showTracks = deferredCards::stage,
        showPlan = ::presentPlan,
        play = { tracks, mode -> aiPlay(player, tracks, mode) }, showPlaylist = deferredCards::stage,
        playback = { withContext(Dispatchers.Main.immediate) { player?.state?.value } })
    private val runner = AiConversationRunner(client, ::currentConfiguration, executor, history, checkpoint,
        ::upsert, { text -> mutable.update { it.copy(progress = text) } }, { state.value.pendingPlan != null }, ::persist, memory::compact)

    private fun initialize() {
        if (initialized || initializing) return
        initializing = true
        scope.launch {
            try {
                val restored = aiRestoreInitial(session, configuration, ::providerState)
                val saved = restored.saved
                saved.optJSONArray("history")?.objects()?.let(history::addAll)
                saved.optJSONObject("checkpoint")?.let { data -> data.keys().forEach { checkpoint.put(it, data.get(it)) } }
                if (saved.optBoolean("historyTruncated")) {
                    checkpoint.put("taskRecoveryRequired", true)
                    history.add(0, JSONObject().put("role", "user").put("content",
                        "MusicOne从任务断点恢复。先用read_task查阅完整目标、约束和覆盖进度，再继续。最近用户要求：" + checkpoint.optString("latestUserRequest")))
                }
                lastOperation = aiUndoForCurrentTurn(saved.optional("lastOperation"), checkpoint, restored.messages)
                runner.closeInterruptedTools()
                val providers = restored.providers
                val selected = restored.configuration
                initialized = true
                mutable.value = AiAssistantUiState(initialized = true, configVisible = !ready(providers, selected.provider), providerId = selected.provider.uiId,
                    providers = providers, messages = restored.messages,
                    currentConversationId = restored.conversationId, conversations = restored.conversations,
                    webEnabled = selected.webSearchEnabled, thinkingEnabled = selected.thinkingEnabled,
                    personality = selected.personality, personalityEnabled = selected.personalityEnabled,
                    thinkingSupported = restored.model?.supportsThinking == true, canUndo = lastOperation != null,
                    thinkingNotice = null,
                    canContinue = saved.optBoolean("running") || saved.optBoolean("canContinue"),
                    hasEarlierMessages = saved.optBoolean("hasEarlierMessages"))
                saved.optional("pendingPlan")?.let { presentPlan(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                initialized = true
                mutable.update { it.copy(initialized = true, error = error.message ?: "无法恢复助手资料") }
            }
        }
    }

    fun bindPlayback(value: MusicPlaybackRuntime) { player = value }
    val playbackState get() = player?.state
    fun backFromConfiguration(): Boolean {
        if (state.value.openedPlaylist != null) { dispatch(AiUiAction.ClosePlaylist); return true }
        if (state.value.historyVisible) { dispatch(AiUiAction.CloseHistory); return true }
        if (!state.value.configVisible || !ready()) return false
        dispatch(AiUiAction.CloseConfig)
        return true
    }
    fun playTrack(track: MusicTrack) { scope.launch { guarded {
        executor.sources.validateTracks(listOf(track))
        val validated = executor.sources.validatePlayable(listOf(track))
        require(validated.playableTracks.isNotEmpty()) { (validated.skipped + validated.unknown).firstOrNull()?.optString("reason") ?: "暂时无法播放此歌曲" }
        aiPlay(player, validated.playableTracks, "play")
    } } }
    fun playPlaylist(playlist: MusicPlaylist, shuffle: Boolean = false) { scope.launch { guarded {
        val validated = executor.sources.validatePlayable(playlist.tracks)
        require(validated.playableTracks.isNotEmpty()) { "没有已确认可播放的歌曲" }
        aiPlay(player, if (shuffle) validated.playableTracks.shuffled() else validated.playableTracks, "play")
        if (validated.skipped.isNotEmpty() || validated.unknown.isNotEmpty())
            mutable.update { it.copy(error = "已跳过 ${validated.skipped.size + validated.unknown.size} 首不可播放或权限暂未确认的歌曲") }
    } } }

    fun dispatch(action: AiUiAction) {
        if (action == AiUiAction.Open) { initialize(); return }
        if (!initialized) return
        if (switching) { if (action == AiUiAction.Stop) switchTask?.cancel(); return }
        when (action) {
            AiUiAction.Open -> Unit
            AiUiAction.LoadEarlierMessages -> historyPaging.load()
            AiUiAction.ShowHistory -> historyNavigation.open()
            AiUiAction.CloseHistory -> historyNavigation.close()
            is AiUiAction.SearchHistory -> historyNavigation.search(action.query)
            is AiUiAction.OpenPlaylist -> mutable.update { it.copy(openedPlaylist = action.playlist) }
            AiUiAction.ClosePlaylist -> mutable.update { it.copy(openedPlaylist = null) }
            is AiUiAction.Input -> mutable.update { it.copy(input = action.value) }
            AiUiAction.Stop -> if (!committing) running?.cancel()
            AiUiAction.ShowConfig -> mutable.update { it.copy(configVisible = true) }
            AiUiAction.CloseConfig, AiUiAction.SaveConfiguration -> if (ready()) {
                configuration.save(currentConfiguration())
                mutable.update { it.copy(configVisible = false, error = null) }
            }
            is AiUiAction.SelectProvider -> if (!state.value.running) selectProvider(action.id)
            is AiUiAction.EditKey -> if (!state.value.running && !selected().refreshing) providerSettings.editKey(provider(), action.value)
            is AiUiAction.EditBaseUrl -> if (!state.value.running && !selected().refreshing && provider() == AiProvider.OTHER) providerSettings.editEndpoint(action.value)
            is AiUiAction.EditModel -> if (!state.value.running && !selected().refreshing && provider() == AiProvider.OTHER) providerSettings.editModel(action.value)
            AiUiAction.FetchModels -> providerSettings.verify(provider(), fetchList = true)
            AiUiAction.RefreshModels -> providerSettings.verify(provider())
            is AiUiAction.SelectModel -> if (!state.value.running && action.id in selected().models) {
                configuration.selectModel(provider(), action.id)
                editProvider { it.copy(selectedModel = action.id, modelInput = action.id) }
                mutable.update { it.copy(thinkingSupported = currentModel()?.supportsThinking == true,
                    thinkingNotice = null) }
            }
            is AiUiAction.Thinking, is AiUiAction.ContextTokens, is AiUiAction.OutputTokens, is AiUiAction.ReasoningEffort ->
                if (!state.value.running && !selected().refreshing) providerSettings.editOptions(provider(), action)
            is AiUiAction.Web, is AiUiAction.AddReference, is AiUiAction.RemoveReference, AiUiAction.ClearMemory -> Unit
            is AiUiAction.EditPersonality -> if (!state.value.running) {
                mutable.update { it.copy(personality = action.value) }; savePersonality()
            }
            is AiUiAction.PersonalityEnabled -> if (!state.value.running) {
                mutable.update { it.copy(personalityEnabled = action.enabled) }; savePersonality()
            }
            AiUiAction.RestoreDefaultPersonality -> if (!state.value.running) {
                mutable.update { it.copy(personality = "", personalityEnabled = false) }; savePersonality()
            }
            AiUiAction.Send -> send()
            AiUiAction.Retry, AiUiAction.ContinueTask -> if (!state.value.running && ready() && state.value.pendingPlan == null) start()
            AiUiAction.NewConversation -> switchConversation(null)
            is AiUiAction.SelectConversation -> switchConversation(action.id)
            is AiUiAction.DeleteConversations -> deleteConversations(action.ids)
            AiUiAction.ConfirmPlan -> resolvePlan(confirm = true)
            AiUiAction.CancelPlan -> resolvePlan(confirm = false)
            AiUiAction.Undo -> prepareUndo()
        }
    }

    private fun send() {
        val text = state.value.input.trim()
        if (text.isEmpty() || state.value.running || !ready() || state.value.pendingPlan != null) return
        val turn = newId()
        // 新问题开启新轮次，上一轮的快捷撤销不再出现在本轮回答下。
        lastOperation = null
        checkpoint.remove("undoTurnId")
        val sentAt = System.currentTimeMillis()
        append(AiChatMessageUi(newId(), "user", text, turnId = turn, sentAt = sentAt))
        history += JSONObject().put("role", "user").put("content", text).put("musiconeTurnId", turn).put("musiconeSentAt", sentAt)
        checkpoint.put("latestUserRequest", text).put("activeTurnId", turn)
        checkpoint.remove("references"); checkpoint.remove("responseOnly"); checkpoint.remove("taskRecoveryRequired")
        checkpoint.put("userRequests", (checkpoint.optJSONArray("userRequests") ?: JSONArray()).put(text))
        checkpoint.put("preferenceCandidates", (checkpoint.optJSONArray("preferenceCandidates") ?: JSONArray())
            .put(JSONObject().put("at", System.currentTimeMillis()).put("text", text)))
        mutable.update { it.copy(input = "", error = null, canUndo = false) }
        start()
    }

    private fun savePersonality() = configuration.savePersonality(state.value.personality, state.value.personalityEnabled)

    private fun start() {
        if (running?.isActive == true) return
        running = scope.launch {
            var completed = false
            mutable.update { it.copy(running = true, canContinue = false, canRetry = false, error = null) }
            try {
                AiReplyService.start(app)
                // 用户已经发送的问题先落盘，生成失败或停止也保留。
                withContext(NonCancellable) { persist(); refreshConversationList() }
                titles.schedule(currentConfiguration())
                memory.refreshContext()
                val more = runner.run()
                completed = !more
                mutable.update { it.copy(canContinue = more, progress = if (more) "任务尚未完成，断点已保存，可继续处理。" else null) }
            } catch (cancelled: CancellationException) {
                runner.closeInterruptedTools()
                mutable.update { it.copy(canContinue = true, progress = "已停止后续操作，已完成的结果保留。") }
            } catch (error: Exception) {
                runner.closeInterruptedTools()
                if (error is AiProviderException && error.kind in setOf(AiFailureKind.AUTHENTICATION, AiFailureKind.CONFIGURATION)) {
                    configuration.invalidate(provider())
                    editProvider { it.copy(verified = false, error = error.message) }
                    mutable.update { it.copy(configVisible = true) }
                }
                mutable.update { it.copy(error = error.message ?: "请求失败", canRetry = true, progress = null) }
            } finally {
                val cards = if (completed) deferredCards.finish() else null
                mutable.update { current -> current.copy(running = false, messages = current.messages.map { row ->
                    val finished = if (row.isThinking) row.copy(isThinking = false) else row
                    if (cards?.id == row.id) finished.copy(tracks = cards.tracks, playlist = cards.playlist) else finished
                }.let { rows -> if (cards != null && rows.none { it.id == cards.id }) rows + cards else rows }) }
                withContext(NonCancellable) { runCatching {
                    persistSnapshot(state.value.copy(running = false)); refreshConversationList()
                } }
                AiReplyService.stop(app)
                if (ready()) {
                    memory.schedule()
                    titles.schedule(currentConfiguration())
                }
            }
        }
    }

    private fun resolvePlan(confirm: Boolean) {
        val id = state.value.pendingPlan?.id ?: return
        if (state.value.running) return
        committing = true
        running = scope.launch {
            mutable.update { it.copy(running = true, error = null) }
            try {
                val result = if (confirm) library.confirm(id) else library.cancel(id)
                if (confirm && result.optString("kind") != "undo") lastOperation = result.getString("operationId")
                if (confirm && result.optString("kind") == "undo") lastOperation = null
                if (lastOperation != null) checkpoint.put("undoTurnId", checkpoint.optString("activeTurnId"))
                else checkpoint.remove("undoTurnId")
                checkpoint.put("lastNativeOperation", result)
                history += JSONObject().put("role", "user").put("content", "MusicOne原生确认结果（事实资料）：$result")
                upsert(aiNativeReceipt(result, confirm).copy(turnId = checkpoint.optString("activeTurnId")))
                if (confirm && result.has("createdPlaylistId")) applyCreatedPlaylist(result.getString("createdPlaylistId"))
                mutable.update { it.copy(pendingPlan = null, canUndo = lastOperation != null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // 冲突后必须重新生成计划；旧授权不延续到任何新计划。
                runCatching { library.cancel(id) }
                upsert(aiFailedNativeReceipt(id, error.message ?: "操作失败"))
                mutable.update { it.copy(pendingPlan = null, error = error.message ?: "操作失败", canContinue = true) }
            } finally {
                committing = false
                withContext(NonCancellable) { persistSnapshot(state.value.copy(running = false)) }
                mutable.update { it.copy(running = false) }
            }
        }
    }

    private fun prepareUndo() {
        if (state.value.running || state.value.pendingPlan != null ||
            aiUndoForCurrentTurn(lastOperation, checkpoint, state.value.messages) == null) return
        running = scope.launch {
            mutable.update { it.copy(running = true, error = null) }
            try { guarded { presentPlan(library.prepareUndo(checkNotNull(lastOperation)).getString("planId")) } }
            finally { withContext(NonCancellable) { persistSnapshot(state.value.copy(running = false)) }; mutable.update { it.copy(running = false) } }
        }
    }

    private suspend fun applyCreatedPlaylist(id: String) {
        val saved = withContext(Dispatchers.IO) { MusicLibraryStore.get(app).state.value.playlists.firstOrNull { it.id == id } } ?: return
        mutable.update { it.copy(messages = aiReplaceSavedPlaylist(it.messages, saved), openedPlaylist = null) }
    }

    private suspend fun presentPlan(id: String) {
        val plan = withContext(Dispatchers.IO) { library.pendingPlan(id) } ?: return
        if (plan.status == "pending") mutable.update { it.copy(pendingPlan = plan.toUiPlan()) }
        else if (plan.status == "executed") {
            // 进程可能在数据库提交后、会话收据落盘前结束，依靠幂等结果恢复。
            val receipt = library.confirm(id)
            lastOperation = id.takeIf { plan.kind != "undo" }
            if (lastOperation != null) checkpoint.put("undoTurnId", checkpoint.optString("activeTurnId"))
            else checkpoint.remove("undoTurnId")
            checkpoint.put("lastNativeOperation", receipt)
            upsert(aiNativeReceipt(receipt, confirmed = true).copy(turnId = checkpoint.optString("activeTurnId")))
            mutable.update { it.copy(canUndo = lastOperation != null) }
        }
    }

    private fun deleteConversations(ids: Set<String>) {
        if (ids.isEmpty() || switching) return
        if (state.value.currentConversationId in ids) {
            switchConversation(null, ids)
            return
        }
        switching = true
        scope.launch {
            try {
                guarded {
                    withContext(Dispatchers.IO) { session.deleteConversations(ids) }
                    refreshConversationList()
                    historyNavigation.search(state.value.historyQuery)
                }
            } finally { switching = false }
        }
    }

    private fun switchConversation(id: String?, deletedIds: Set<String> = emptySet()) {
        if (switching || (id == state.value.currentConversationId && deletedIds.isEmpty())) return
        switching = true
        historyNavigation.close()
        historyPaging.cancel()
        val previous = running
        switchTask = scope.launch {
            try {
                // 切换只停止模型；已经确认的事务必须等收据保存完毕。
                if (committing) previous?.join() else previous?.cancelAndJoin()
                mutable.update { it.copy(running = true, progress = null) }
                if (ready()) memory.schedule()
                persistSnapshot(state.value.copy(running = false))
                withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) {
                    if (id == null) session.newConversation() else session.selectConversation(id)
                    if (deletedIds.isNotEmpty()) session.deleteConversations(deletedIds)
                    session.read()
                }
                history.clear(); checkpoint.keys().asSequence().toList().forEach(checkpoint::remove)
                saved.optJSONArray("history")?.objects()?.let(history::addAll)
                saved.optJSONObject("checkpoint")?.let { data -> data.keys().forEach { checkpoint.put(it, data.get(it)) } }
                if (saved.optBoolean("historyTruncated")) history.add(0, JSONObject().put("role", "user").put("content",
                    "对话从断点恢复，请先read_task读取完整任务与约束。"))
                runner.closeInterruptedTools()
                lastOperation = aiUndoForCurrentTurn(saved.optional("lastOperation"), checkpoint,
                    saved.optJSONArray("messages")?.objects()?.map { it.toAiMessage() }.orEmpty())
                mutable.update { it.copy(messages = saved.optJSONArray("messages")?.objects()?.map { row -> row.toAiMessage() }.orEmpty(),
                    input = "", pendingPlan = null, error = null, progress = null, canUndo = lastOperation != null, canRetry = false,
                    canContinue = saved.optBoolean("running") || saved.optBoolean("canContinue"), references = emptyList(), openedPlaylist = null,
                    hasEarlierMessages = saved.optBoolean("hasEarlierMessages"), loadingEarlierMessages = false) }
                saved.optional("pendingPlan")?.let { presentPlan(it) }
                refreshConversationList()
                if (deletedIds.isNotEmpty()) historyNavigation.open()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = error.message ?: "切换对话失败", progress = null) }
            } finally {
                switching = false
                mutable.update { it.copy(running = false, progress = null) }
            }
        }
    }

    private suspend fun refreshConversationList() {
        val (id, conversations) = withContext(Dispatchers.IO) { session.currentConversationId to session.listConversations() }
        mutable.update { it.copy(currentConversationId = id,
            conversations = conversations.map { item -> AiConversationUi(item.id, item.title, item.updatedAt) }) }
    }

    private suspend fun persist() = persistSnapshot(state.value)
    private suspend fun persistSnapshot(snapshot: AiAssistantUiState) {
        val protocol = history.toList()
        val task = JSONObject(checkpoint.toString())
        val operation = lastOperation
        val conversation = session.currentConversationId
        withContext(Dispatchers.IO) { session.writeSnapshot(protocol, task, snapshot.messages, snapshot.running,
            snapshot.canContinue, snapshot.pendingPlan?.id, operation, expectedConversationId = conversation) }
    }
    private fun provider() = AiProvider.entries.firstOrNull { it.uiId == state.value.providerId } ?: AiProvider.DEEPSEEK
    private fun selected() = state.value.providers.first { it.id == state.value.providerId }
    private fun currentConfiguration() = selected().applyRequestOptions(AiConfiguration(provider(), selected().selectedModel,
        state.value.webEnabled, personality = state.value.personality, personalityEnabled = state.value.personalityEnabled, baseUrl = selected().baseUrl))
    private fun currentModel(config: AiConfiguration = configuration.configuration(provider()).copy(modelId = selected().selectedModel)) = configuration.models(config.provider).firstOrNull { it.id == config.modelId }
    private fun ready(providers: List<AiProviderUiState> = state.value.providers, selected: AiProvider = provider()) = providers.any {
        it.id == selected.uiId && it.verified && it.selectedModel.isNotBlank() && it.selectedModel in it.models && it.requestOptionsError() == null
    }
    private fun providerState(provider: AiProvider): AiProviderUiState = providerSettings.state(provider)
    private fun refreshProviderCapabilities() {
        val web = configuration.webSearchAvailable() && state.value.providers.any { it.id == "deepseek" && it.verified }
        mutable.update { it.copy(webEnabled = web, thinkingEnabled = selected().thinkingEnabled, thinkingNotice = null) }
    }
    private fun selectProvider(id: String) {
        val target = AiProvider.entries.firstOrNull { it.uiId == id } ?: return
        configuration.selectProvider(target)
        val saved = configuration.configuration(target)
        mutable.update { it.copy(providerId = id, webEnabled = configuration.webSearchAvailable() && state.value.providers.any { p -> p.id == "deepseek" && p.verified }, thinkingEnabled = saved.thinkingEnabled,
            thinkingSupported = currentModel(saved)?.supportsThinking == true, thinkingNotice = null, error = null) }
    }
    private fun editProvider(change: (AiProviderUiState) -> AiProviderUiState) = updateProvider(provider(), change)
    private fun updateProvider(provider: AiProvider, change: (AiProviderUiState) -> AiProviderUiState) {
        mutable.update { it.copy(providers = it.providers.map { old -> if (old.id == provider.uiId) change(old) else old }) }
    }
    private fun append(message: AiChatMessageUi) { mutable.update { it.copy(messages = it.messages + message) } }
    private fun upsert(message: AiChatMessageUi) { mutable.update { old ->
        old.copy(messages = if (old.messages.any { it.id == message.id }) old.messages.map {
            if (it.id == message.id) message.copy(tracks = (it.tracks + message.tracks).distinctBy { track -> track.source.id to track.id },
                playlist = message.playlist ?: it.playlist, receipt = message.receipt ?: it.receipt) else it
        } else old.messages + message)
    } }
    private suspend fun guarded(action: suspend () -> Unit) { try { action() } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(error = error.message ?: "操作失败") }
    } }
    private fun newId() = UUID.randomUUID().toString()
    companion object {
        @Volatile private var instance: AiAssistantController? = null
        fun get(context: Context): AiAssistantController = instance ?: synchronized(this) {
            instance ?: AiAssistantController(context).also { instance = it }
        }
    }
}

private val AiProvider.uiId get() = name.lowercase()
