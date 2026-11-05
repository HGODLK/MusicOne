package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun AiMessageCard(message: AiChatMessageUi, progress: String? = null, onPlayTrack: (MusicTrack) -> Unit = {},
    onOpenPlaylist: (MusicPlaylist) -> Unit = {}, showCards: Boolean = true, streaming: Boolean = false,
    animateCards: Boolean = false, onCardsEntered: () -> Unit = {}) {
    val animateThisMount = remember(message.id) { animateCards }
    val preparer = rememberAiMarkdownPreparer()
    val readyAtEntry = remember(message, preparer) { preparer.ready(listOf(message)) }
    val prepared by produceState(readyAtEntry || streaming, message, preparer, streaming) {
        if (!streaming && !readyAtEntry) preparer.prepare(listOf(message))
        value = true
    }
    if (!streaming && !prepared) {
        // 快速跳到尚未预热的历史项时先排版，不经历原文到富文本的二次变形。
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        }
        return
    }
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            AiUserBubble(message.text, aiUserBubbleMaxWidth(maxWidth))
        } else Column(Modifier.fillMaxWidth()) {
            AiReasoningSection(message.reasoning, message.isThinking, message.id)
            val receiptDetails = message.receipt != null || (message.isNativeReceipt && !message.result.isNullOrBlank())
            if (!progress.isNullOrBlank() || message.progressSteps.isNotEmpty() || receiptDetails)
                AiExecutionSection(progress, message.progressSteps, message.id, hasDetails = receiptDetails, details = {
                        message.receipt?.let { AiChangeDetails(it.groups, it.summary) }
                            ?: message.result?.takeIf { message.isNativeReceipt && it.isNotBlank() }?.let {
                                // 旧回执保留逐行字段，在执行步骤内展开，不经 Markdown 串行。
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                    })
            val hasDisclosure = message.reasoning.isNotBlank() || message.isThinking ||
                !progress.isNullOrBlank() || message.progressSteps.isNotEmpty() || receiptDetails
            val hasBody = message.text.isNotBlank() ||
                message.receipt == null && !message.isNativeReceipt && !message.result.isNullOrBlank() ||
                showCards && (message.tracks.isNotEmpty() || message.playlist != null)
            if (hasBody) Column(Modifier.padding(top = if (hasDisclosure) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message.text.isNotBlank()) AiMarkdown(message.text, streaming = streaming)
            if (message.receipt == null && !message.isNativeReceipt)
                message.result?.takeIf { it.isNotBlank() }?.let { AiMarkdown(it) }
            if (showCards && (message.tracks.isNotEmpty() || message.playlist != null)) {
                val cards: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        message.tracks.forEach { AiTrackCard(it, onPlayTrack) }
                        message.playlist?.let { AiPlaylistCard(it, onOpenPlaylist) }
                    }
                }
                // 新回复保留底部入场；历史和已进入过视口的卡片直接复用终点画面。
                SideEffect { onCardsEntered() }
                if (animateThisMount && !ExperiencePreferences.options.reduceMotion) SearchResultEntrance(cards) else cards()
            }
            }
        }
    }
}

@Composable
internal fun AiExecutionSection(progress: String?, progressSteps: List<String> = emptyList(), messageId: String = "pending",
    hasDetails: Boolean = false, details: @Composable () -> Unit = {}) {
    var stepsExpanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val sectionHeight = LocalConfiguration.current.screenHeightDp.dp * .5f
    Column(Modifier.fillMaxWidth()) {
        if (progressSteps.isNotEmpty() || !progress.isNullOrBlank() || hasDetails) {
            AiDisclosureRow("执行步骤", stepsExpanded, busy = !progress.isNullOrBlank(), onClick = { stepsExpanded = !stepsExpanded })
            AnimatedVisibility(stepsExpanded,
                enter = expandVertically(musicMotion(280)) + fadeIn(musicMotion(220)),
                exit = shrinkVertically(musicMotion(240)) + fadeOut(musicMotion(180))) {
            Column(Modifier.fillMaxWidth().heightIn(max = sectionHeight)
                .verticalScroll(rememberScrollState()).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                progressSteps.filter(String::isNotBlank).forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            progress?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (hasDetails) details()
            }
            }
        }
    }
}

/** 执行步骤复用紧凑行高与箭头旋转，保留减少动态效果设置。 */
@Composable
internal fun AiDisclosureRow(label: String, expanded: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        animationSpec = if (ExperiencePreferences.options.reduceMotion) snap() else musicMotion(220),
        label = "AI 展开箭头")
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClickLabel = if (expanded) "收起$label" else "展开$label", onClick = onClick)
        .semantics(mergeDescendants = true) { stateDescription = if (expanded) "已展开" else "已收起" }
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.ExpandMore, contentDescription = null,
            modifier = Modifier.size(18.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
