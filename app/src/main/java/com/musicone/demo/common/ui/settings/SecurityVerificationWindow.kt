package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** 验证窗口只采样下方登录层，避免将自身递归录入模糊背景。 */
@Composable
internal fun SecurityVerificationWindow(state: PlatformSettingsUiState, actions: PlatformLoginActions,
    content: @Composable () -> Unit) {
    val loginLayer = rememberGraphicsLayer()
    val activeVerification = state.securityVerification
    val nativeWebWindow = state.loginSource == MusicSource.QQ
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var retained by remember { mutableStateOf(state.securityVerification) }
    state.securityVerification?.let { retained = it }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight = minOf(620.dp, maxHeight * .78f)
        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }
            .drawWithContent { loginLayer.record { this@drawWithContent.drawContent() }; drawLayer(loginLayer) }) { content() }
        if (nativeWebWindow && activeVerification != null) {
            QqVerificationDialog(
                activeVerification,
                state.working,
                state.message,
                actions.onCancelSecurityVerification,
                actions.onCompleteSecurityVerification,
            )
        }
        AnimatedVisibility(!nativeWebWindow && state.securityVerification != null,
            enter = fadeIn(musicMotion(240)), exit = fadeOut(musicMotion(220))) {
            BackHandler(enabled = state.securityVerification != null) { actions.onCancelSecurityVerification() }
            val verification = retained
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .20f))
                .clickable(remember { MutableInteractionSource() }, null) { actions.onCancelSecurityVerification() },
                contentAlignment = Alignment.Center) {
                MusicOneBackdropGlass(loginLayer, bounds,
                    modifier = Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().height(panelHeight)
                        .animateEnterExit(enter = scaleIn(musicMotion(300), initialScale = .94f) + slideInVertically(musicMotion(300)) { it / 20 },
                            exit = scaleOut(musicMotion(220), targetScale = .94f) + slideOutVertically(musicMotion(220)) { it / 20 })
                        .clickable(remember { MutableInteractionSource() }, null) {},
                    shape = RoundedCornerShape(28.dp), blurRadius = 24.dp,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .72f)) {
                    retained?.let { PlatformSecurityVerificationScreen(it, state.working, state.message,
                        actions.onCancelSecurityVerification, actions.onCompleteSecurityVerification) }
                }
            }
        }
    }
}
