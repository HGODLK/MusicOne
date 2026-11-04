package com.musicone

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SourceSettingsScreen(
    state: PlatformSettingsUiState,
    onBack: () -> Unit,
    onSelectSource: (MusicSource) -> Unit,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenManagement: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions = androidx.compose.runtime.remember(state.sessionRevision) { PlatformPreferences(context).read().sessions }
    fun label(source: MusicSource): String = if (state.selectedSource == source) selectedSessionLabel(state)
        else sessions[source]?.account?.nickname?.let { "已登录 · $it" } ?: "未登录"
    val accountActions = PlatformAccountActionsUi(state.selectedSource, state.selectedSource.supportsAuthentication,
        state.account != null, state.sourceSelected, state.sessionStatus == SessionStatus.CHECKING)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { SettingsHeader("主音源", onBack) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音乐平台", style = MaterialTheme.typography.headlineSmall)
                    Text("选择浏览入口，正在播放的歌曲与队列会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    MAIN_MUSIC_SOURCES.forEach { source ->
                        PlatformChoice(
                            source,
                            state.sourceSelected && state.selectedSource == source,
                            true,
                            if (source == MusicSource.LOCAL) "无需账号 · 导入音乐即可使用" else label(source),
                            { onSelectSource(source) },
                        )
                    }
                    Crossfade(accountActions, animationSpec = musicMotion(220), label = "平台账号操作衔接") { accountState ->
                        if (accountState.selectedSource == MusicSource.LOCAL) {
                            Text("本地音乐无需登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (!accountState.supportsAuthentication) {
                            Text("当前音源不提供登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (accountState.hasAccount) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = onOpenLogin) { Text("重新登录") }
                                OutlinedButton(onClick = onLogout) { Text("退出登录") }
                            }
                        } else {
                            Button(
                                onClick = onOpenLogin,
                                enabled = accountState.sourceSelected && !accountState.checking,
                            ) {
                                Text(if (accountState.sourceSelected) "登录${accountState.selectedSource.label}" else "请先选择音源")
                            }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = onOpenManagement) { Text("管理音源包") } }
            state.message?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
            }
        }
    }
}

private data class PlatformAccountActionsUi(
    val selectedSource: MusicSource,
    val supportsAuthentication: Boolean,
    val hasAccount: Boolean,
    val sourceSelected: Boolean,
    val checking: Boolean,
)

private fun selectedSessionLabel(state: PlatformSettingsUiState): String = when (state.sessionStatus) {
    SessionStatus.CHECKING -> "正在检查登录状态"
    SessionStatus.CONNECTED -> state.account?.nickname?.let { "已登录 · $it" } ?: "未登录"
    SessionStatus.SIGNED_OUT -> "未登录"
}

@Composable
private fun PlatformChoice(
    source: MusicSource,
    selected: Boolean,
    enabled: Boolean,
    status: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Color(source.accent), modifier = Modifier.size(12.dp)) {}
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    PlatformAvailabilityBadge(source)
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f),
            )
        }
    }
}

@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit, singleLine: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (LocalUnifiedBack.current) Spacer(Modifier.size(48.dp)) else
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        Text(title, style = MaterialTheme.typography.displayMedium.copy(fontSize = 30.sp),
            modifier = Modifier.padding(start = 5.dp).then(if (singleLine) Modifier.weight(1f) else Modifier),
            maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
    }
}
