package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun NeteaseLoginScreen(state: PlatformSettingsUiState, actions: PlatformLoginActions) {
    LaunchedEffect(state.loginMethod, state.qrStatus) {
        if (state.loginMethod == PlatformLoginMethod.QR_CODE && state.qrStatus == PlatformQrLoginStatus.IDLE) {
            actions.onStartQrLogin()
        }
    }
    DisposableEffect(Unit) { onDispose(actions.onStopQrLogin) }
    Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { SettingsHeader(state.loginSource.label, actions.onBack) }
            item { PlatformLoginPageTitle(state.loginSource.label, "支持扫码和手机号验证码登录。") }
            item { PlatformLoginMethodButtons(state.loginMethod, actions.onSelectLoginMethod) }
            if (state.loginMethod == PlatformLoginMethod.QR_CODE) {
                item { PlatformQrLoginContent(state, actions.onStartQrLogin) }
            } else {
                item { PlatformSmsLoginContent(state, actions) }
            }
        }
    }
}
