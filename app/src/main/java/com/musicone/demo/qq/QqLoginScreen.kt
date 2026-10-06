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

/** QQ 仅保留短信表单，不再创建原生授权页及登录方式子层级。 */
@Composable
internal fun QqLoginScreen(state: PlatformSettingsUiState, actions: PlatformLoginActions) {
    DisposableEffect(Unit) {
        actions.onLocalBackChange(null)
        onDispose { actions.onLocalBackChange(null) }
    }
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
        .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsHeader("", actions.onBack)
        Spacer(Modifier.height(40.dp))
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
            Box(Modifier.padding(20.dp)) { PlatformSmsLoginContent(state, actions) }
        }
        Spacer(Modifier.height(110.dp))
    }
}
