package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun AiAssistantScreen(state: AiAssistantUiState, onAction: (AiUiAction) -> Unit,
    onBack: () -> Unit, onPlayTrack: (MusicTrack) -> Unit, bottomInset: Dp,
    onOpenPlaylist: (MusicPlaylist) -> Unit = {}) {
    LaunchedEffect(Unit) { onAction(AiUiAction.Open) }
    val provider = state.providers.firstOrNull { it.id == state.providerId }
    val ready = provider?.let { it.verified && it.selectedModel.isNotBlank() && it.selectedModel in it.models && it.requestOptionsError() == null } == true
    val configuration = state.configVisible || !ready
    val bottomClearance = rememberAiBottomClearance(bottomInset)
    val back = { if (state.historyVisible) onAction(AiUiAction.CloseHistory)
        else if (configuration && ready) onAction(AiUiAction.CloseConfig) else onBack() }
    BackHandler(onBack = back)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 状态栏不再由外层统一避让：聊天顶栏要铺到屏幕最顶，其余二级页各自保留原边距。
        Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            // 恢复完成前不挂载配置或聊天，避免默认配置页切成聊天的首开过渡。
            if (state.initialized) AnimatedContent(if (state.historyVisible) 2 else if (configuration) 1 else 0,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { settingsPageTransform(targetState > initialState) }, label = "AI 二级页面切换") { page ->
                when (page) {
                    2 -> AiInsetPage(bottomClearance) { AiHistoryScreen(state, onAction, back) }
                    1 -> AiInsetPage(bottomClearance) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
                                SettingsHeader("AI 设置", back, singleLine = true)
                            }
                            AiConfigurationScreen(state, onAction, Modifier.weight(1f))
                        }
                    }
                    else -> AiChatScreen(state, onAction, onPlayTrack, Modifier.fillMaxSize(), bottomInset, onOpenPlaylist) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // 返回由根层统一按钮承担，这里只保留同尺寸占位，避免两个返回键叠在一起。
                            if (LocalUnifiedBack.current) Spacer(Modifier.size(48.dp))
                            else IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                            }
                            AiConversationControls(state, onAction, Modifier.weight(1f))
                            IconButton(onClick = { onAction(AiUiAction.ShowConfig) }) {
                                Icon(Icons.Default.Settings, contentDescription = "配置 AI 服务")
                            }
                        }
                    }
                }
            }
        }
    }
    state.pendingPlan?.let { plan ->
        AiGlassDialog(onDismissRequest = { if (!state.running) onAction(AiUiAction.CancelPlan) },
            title = { Text(plan.title) },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("确认后才会保存以下修改，请核对完整操作明细。") }
                item {
                    if (plan.groups.isNotEmpty()) AiChangeDetails(plan.groups, plan.summary)
                    else { plan.summary?.let { Text(it) }; plan.details.forEach { Text(it) } }
                }
            } },
            confirmButton = { TextButton(onClick = { onAction(AiUiAction.ConfirmPlan) }, enabled = !state.running) { Text(if (state.running) "正在处理…" else "确认执行") } },
            dismissButton = { TextButton(onClick = { onAction(AiUiAction.CancelPlan) }, enabled = !state.running) { Text("取消") } })
    }
}

/** 设置和历史沿用原来的阅读宽度与边距，只有聊天页改成整屏顶栏。 */
@Composable
private fun AiInsetPage(bottomClearance: () -> Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().padding(horizontal = 16.dp)
            .aiBottomClearance(bottomClearance).padding(top = 12.dp), content = content)
    }
}
