package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceRoute
import com.musicone.sourceintegration.SourceExternalSurface
import com.musicone.sourceintegration.rememberSourceMembership

/** 未提供登录页面时只显示可验证的账号状态，不猜测登录参数或回落到空入口。 */
@Composable
internal fun SupplementalDefaultAccount(state: SupplementalState, model: SupplementalSourceModel, onBack: () -> Unit) {
    val member = rememberSourceMembership(model.entry)
    if (model.entry.authenticationPage != null) {
        SourceExternalSurface(model.instance, SourceRoute("authentication"), Modifier.fillMaxSize(),
            mode = "authentication", onExit = onBack)
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LibrarySettingsGroup {
            Text(state.account?.displayName ?: "尚未登录", style = MaterialTheme.typography.titleMedium)
            state.account?.membership?.takeIf { member == true && it.isNotBlank() }?.let { Text(it) }
            Text(model.entry.descriptor.name, style = MaterialTheme.typography.bodyMedium)
            if (state.account == null) MainSourceInlineMessage("此音源未提供登录页面，请通过音源自定义账号页完成登录。")
            else TextButton(onClick = { model.logout() }, enabled = !state.busy) { Text("退出登录") }
        }
        state.error?.let { MainSourceInlineMessage(it) }
        state.message?.let { MainSourceInlineMessage(it) }
    }
}
