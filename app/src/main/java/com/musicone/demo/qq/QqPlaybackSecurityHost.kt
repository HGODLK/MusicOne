package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 在应用根层复用既有 QQ 验证窗口，不把播放恢复状态塞进页面主 Composable。 */
@Composable
internal fun QqPlaybackSecurityHost() {
    val context = LocalContext.current
    val state by QqSessionRequestCoordinator.verification.collectAsStateWithLifecycle()
    val verification = state ?: return
    val uiState = remember(verification.challenge) { verification.challenge.toUiState() }
    QqVerificationDialog(
        verification = uiState,
        working = false,
        message = verification.message,
        onCancel = QqSessionRequestCoordinator::cancel,
        onComplete = { result ->
            val credential = QqSessionRequestCoordinator.complete(result) ?: return@QqVerificationDialog
            val preferences = PlatformPreferences(context)
            val session = preferences.readSession(MusicSource.QQ)
            session.account?.let { preferences.saveSession(credential, it) }
        },
    )
}
