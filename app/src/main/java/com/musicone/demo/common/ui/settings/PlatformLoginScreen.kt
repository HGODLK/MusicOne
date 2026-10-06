package com.musicone.demo

import androidx.compose.runtime.Composable

internal data class PlatformLoginActions(
    val onBack: () -> Unit,
    val onPhoneChange: (String) -> Unit,
    val onCountryCodeChange: (String) -> Unit,
    val onCaptchaChange: (String) -> Unit,
    val onSendCaptcha: () -> Unit,
    val onLogin: () -> Unit,
    val onSelectLoginMethod: (PlatformLoginMethod) -> Unit,
    val onStartQrLogin: () -> Unit,
    val onStopQrLogin: () -> Unit,
    val onCancelSecurityVerification: () -> Unit,
    val onCompleteSecurityVerification: (PlatformSecurityVerificationResult) -> Unit,
    val onLocalBackChange: ((() -> Unit)?) -> Unit = {},
)

@Composable
internal fun PlatformLoginScreen(
    state: PlatformSettingsUiState,
    onBack: () -> Unit,
    onPhoneChange: (String) -> Unit,
    onCountryCodeChange: (String) -> Unit,
    onCaptchaChange: (String) -> Unit,
    onSendCaptcha: () -> Unit,
    onLogin: () -> Unit,
    onSelectLoginMethod: (PlatformLoginMethod) -> Unit,
    onStartQrLogin: () -> Unit,
    onStopQrLogin: () -> Unit,
    onCancelSecurityVerification: () -> Unit,
    onCompleteSecurityVerification: (PlatformSecurityVerificationResult) -> Unit,
    onLocalBackChange: ((() -> Unit)?) -> Unit = {},
) {
    val actions = PlatformLoginActions(
        onBack,
        onPhoneChange,
        onCountryCodeChange,
        onCaptchaChange,
        onSendCaptcha,
        onLogin,
        onSelectLoginMethod,
        onStartQrLogin,
        onStopQrLogin,
        onCancelSecurityVerification,
        onCompleteSecurityVerification,
        onLocalBackChange,
    )
    SecurityVerificationWindow(state, actions) {
        when (state.loginSource) {
            MusicSource.NETEASE -> NeteaseLoginScreen(state, actions)
            MusicSource.QQ -> QqLoginScreen(state, actions)
            MusicSource.KUGOU -> KugouLoginScreen(state, actions)
        }
    }
}
