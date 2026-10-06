package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val LocalAccountEntry = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 头像入口只管理平台选择，登录表单仍复用既有流程。 */
@Composable
internal fun AccountEntryHost(
    state: PlatformSettingsUiState, navigation: SettingsNavigation,
    select: (MusicSource) -> Unit, login: (MusicSource) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { PlatformPreferences(context) }
    CompositionLocalProvider(LocalAccountEntry provides { navigation.choosingSource = true }) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (navigation.choosingSource) {
                Box(Modifier.fillMaxSize().clickable { navigation.choosingSource = false },
                    contentAlignment = Alignment.Center) {
                    MusicOneBackdropGlass(LocalPlayerBackdropTexture.current, LocalPlayerBackdropBounds.current,
                        Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth()
                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null) {}, shape = RoundedCornerShape(28.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("选择主音源", style = MaterialTheme.typography.headlineSmall)
                            Text("选择后登录对应平台", style = MaterialTheme.typography.bodySmall)
                            MusicSource.entries.forEach { source ->
                                val account = remember(source, state.sessionRevision) { preferences.readSession(source).account }
                                ListItem(headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(source.label)
                                        PlatformAvailabilityBadge(source)
                                    }
                                },
                                    supportingContent = { Text(account?.nickname ?: "未登录") },
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                    modifier = Modifier.clickable {
                                        select(source)
                                        navigation.choosingSource = false
                                        if (account == null) {
                                            login(source)
                                            navigation.openPlatformLogin(fromAvatar = true)
                                        }
                                    })
                            }
                            TextButton(onClick = { navigation.choosingSource = false }) { Text("取消") }
                        }
                    }
                }
                BackHandler { navigation.choosingSource = false }
            }
        }
    }
}
