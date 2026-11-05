package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiChatScreen(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit,
    onPlayTrack: (MusicTrack) -> Unit, modifier: Modifier, bottomInset: Dp,
    onOpenPlaylist: (MusicPlaylist) -> Unit = {}, header: @Composable () -> Unit = {}) {
    val backdrop = rememberGraphicsLayer()
    val composerBackdrop = LocalAiComposerBackdrop.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val bottomClearance = rememberAiBottomClearance(bottomInset)
    val presentation = remember(state.currentConversationId) { AiVisibleMessagesCache() }
    val projectedMessages = remember(state.messages) { presentation.update(state.messages) }
    val shownCards = remember(state.currentConversationId) {
        state.messages.filter { it.playlist != null || it.tracks.isNotEmpty() }.mapTo(mutableSetOf()) { it.id }
    }
    val preparer = rememberAiMarkdownPreparer()
    var initialPrepared by remember(state.currentConversationId, preparer) {
        mutableStateOf(preparer.ready(projectedMessages.takeLast(6)))
    }
    var messages by remember(state.currentConversationId, preparer) {
        mutableStateOf(if (initialPrepared) projectedMessages else emptyList())
    }
    val listState = remember(state.currentConversationId) {
        LazyListState(firstVisibleItemIndex = (projectedMessages.lastIndex +
            if (state.hasEarlierMessages) 1 else 0).coerceAtLeast(0))
    }
    val autoFollow = rememberAiChatAutoFollow(listState, state.messages.firstOrNull()?.id)
    LaunchedEffect(projectedMessages, preparer) {
        if (!initialPrepared) {
            // 首屏只准备末尾六条，不先挂顶端再滚到底部。
            preparer.prepare(projectedMessages.takeLast(6))
            initialPrepared = true
        } else if (messages.isNotEmpty() && projectedMessages.firstOrNull()?.id != messages.first().id) {
            val added = projectedMessages.indexOfFirst { it.id == messages.first().id }
            if (added > 0) {
                preparer.prepare(projectedMessages.take(added))
                shownCards.addAll(projectedMessages.take(added).map { it.id })
            }
        }
        messages = projectedMessages
    }
    val pagingState by rememberUpdatedState(state)
    val pagingAction by rememberUpdatedState(onAction)
    val pagingLayoutReady by rememberUpdatedState(messages.size == projectedMessages.size && initialPrepared)
    LaunchedEffect(listState) {
        var requestedAtSize = -1
        // 只在向历史方向滚动时补页，末尾动画经过顶部索引不能触发历史加载。
        snapshotFlow { Triple(listState.firstVisibleItemIndex,
            listState.isScrollInProgress && listState.lastScrolledBackward, pagingState.messages.size) }
            .collect { (first, scrolling, size) ->
                if (scrolling && pagingLayoutReady && first <= 3 && pagingState.hasEarlierMessages &&
                    !pagingState.loadingEarlierMessages && requestedAtSize != size) {
                    requestedAtSize = size
                    pagingAction(AiUiAction.LoadEarlierMessages)
                }
            }
    }
    val activeAssistant = messages.lastOrNull()?.takeIf { it.role == "assistant" }?.id
    CompositionLocalProvider(LocalMiniPlayerGlassSampling provides rememberMiniPlayerGlassSampling(backdrop)) {
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val pageBottom = bottomClearance()
        val loose = constraints.copy(minHeight = 0, minWidth = 0)
        // 玻璃顶栏铺满整屏，聊天正文和输入框仍保持 800dp 阅读宽度与 16dp 边距。
        val readingInset = with(density) { aiChatReadingInset(constraints.maxWidth.toDp()) }
        val composer = subcompose("composer") {
            val samplingLayer = composerBackdrop?.layer ?: backdrop
            CompositionLocalProvider(LocalMiniPlayerGlassSampling provides rememberMiniPlayerGlassSampling(samplingLayer)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = AiReadingWidth).fillMaxWidth().padding(horizontal = AiReadingMargin)) {
                        AiChatComposer(state, onAction, samplingLayer, bounds, Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }.map { it.measure(loose) }
        val composerHeight = composer.maxOfOrNull { it.height } ?: 0
        val effectiveComposerHeight = maxOf(with(density) { composerHeight.toDp() }, AiChatDefaultComposerHeight)
        val toolbar = subcompose("header") {
            // 顶栏玻璃整条铺满屏幕，含状态栏；文字仍按阅读宽度居中，内容从玻璃下方滚过。
            MusicOneBackdropGlass(backdrop, bounds, Modifier.fillMaxWidth(), RectangleShape, border = null) {
                Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.Center) {
                    // 12dp 上边距与根层统一返回按钮同高；左右 24dp 是原阅读列的 16dp 加按钮自身 8dp。
                    Box(Modifier.widthIn(max = AiReadingWidth).fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)) { header() }
                }
            }
        }.map { it.measure(loose) }
        val headerHeight = toolbar.maxOfOrNull { it.height } ?: 0
        val chat = subcompose("messages") {
        if (!initialPrepared) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else LazyColumn(Modifier.fillMaxSize().then(autoFollow).onGloballyPositioned { bounds = it.boundsInRoot() }
            .aiChatBackdrop(backdrop, { bounds }, composerBackdrop), state = listState,
            // 列表本身保持整屏，为顶栏玻璃提供整屏采样背景；正文用左右留白回到阅读宽度。
            // 底部预留好消息框默认高度，避免首开滑入或动态测量时差挤压信息流导致瞬移。
            contentPadding = PaddingValues(start = readingInset, end = readingInset,
                top = with(density) { headerHeight.toDp() } + 24.dp,
                bottom = effectiveComposerHeight + pageBottom + 24.dp)) {
            if (state.hasEarlierMessages || state.loadingEarlierMessages) item(key = "earlier-messages") {
                Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { onAction(AiUiAction.LoadEarlierMessages) }, enabled = !state.loadingEarlierMessages) {
                        Text(if (state.loadingEarlierMessages) "正在加载更早的消息…" else "加载更早的消息")
                    }
                }
            }
            if (messages.isEmpty()) item(key = "empty-conversation") {
                AiChatWelcomeContent(webEnabled = state.webEnabled, onSelectPrompt = { prompt ->
                    onAction(AiUiAction.Input(prompt))
                    onAction(AiUiAction.Send)
                })
            }
            itemsIndexed(messages, key = { _, message -> message.id }, contentType = { _, message -> message.role }) { index, message ->
                Box(Modifier.padding(top = aiChatMessageGap(messages.getOrNull(index - 1), message).dp)) {
                AiMessageCard(message,
                    progress = state.progress.takeIf { state.running && message.id == activeAssistant }, onPlayTrack = onPlayTrack,
                    onOpenPlaylist = onOpenPlaylist, streaming = state.running && message.id == activeAssistant,
                    showCards = !state.running || message.turnId != messages.lastOrNull()?.turnId,
                    animateCards = message.id !in shownCards, onCardsEntered = { shownCards.add(message.id) })
                }
            }
            if (state.running && activeAssistant == null) item(key = "pending") {
                Box(Modifier.padding(top = 24.dp)) { AiExecutionSection(state.progress ?: "正在准备回复") }
            }
            state.error?.let { error -> item(key = "error") {
                Text(error, modifier = Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            } }
            if (state.canContinue || state.canRetry || state.canUndo) item(key = "task-actions") {
                FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canContinue) TextButton(onClick = { onAction(AiUiAction.ContinueTask) }, enabled = !state.running) { Text("继续任务") }
                    if (state.canRetry) TextButton(onClick = { onAction(AiUiAction.Retry) }, enabled = !state.running) { Text("重试") }
                    if (state.canUndo) TextButton(onClick = { onAction(AiUiAction.Undo) }, enabled = !state.running) { Text("撤销上次操作") }
                }
            }
        }
        }.map { it.measure(constraints) }
        // 同帧测量输入区与玻璃顶栏，列表留白直接使用其动画中高度，不再首帧回写布局。
        layout(constraints.maxWidth, constraints.maxHeight) {
            chat.forEach { it.placeRelative(0, 0) }
            toolbar.forEach { it.placeRelative(0, 0, zIndex = 2f) }
            // 列表仍绘制到播放条后方，仅输入框与末尾留白避让播放条，避免玻璃采到空白。
            val defaultHeightPx = with(density) { AiChatDefaultComposerHeight.roundToPx() }
            composer.forEach {
                val height = maxOf(it.height, defaultHeightPx)
                it.placeRelative(0, (constraints.maxHeight - height - pageBottom.roundToPx()).coerceAtLeast(0), zIndex = 1f)
            }
        }
    }
    }
}
