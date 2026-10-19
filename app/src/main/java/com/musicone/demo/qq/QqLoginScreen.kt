package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 三种登录方式共用现有卡片，手机和平板保持同一宽度约束。 */
@Composable
internal fun QqLoginScreen(state: PlatformSettingsUiState, actions: PlatformLoginActions) {
    DisposableEffect(Unit) {
        actions.onLocalBackChange(null)
        onDispose {
            actions.onStopQrLogin()
            actions.onLocalBackChange(null)
        }
    }
    LaunchedEffect(state.loginMethod, state.qrStatus) {
        if (state.loginMethod != PlatformLoginMethod.SMS && state.qrStatus == PlatformQrLoginStatus.IDLE) {
            actions.onStartQrLogin()
        }
    }
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
        .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsHeader("", actions.onBack)
        Spacer(Modifier.height(40.dp))
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                QqLoginMethodSelector(state.loginMethod, actions.onSelectLoginMethod, enabled = !state.working)
                if (state.loginMethod == PlatformLoginMethod.SMS) {
                    PlatformSmsLoginContent(state, actions)
                } else {
                    PlatformQrLoginContent(state, actions.onStartQrLogin)
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}
