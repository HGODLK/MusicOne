package com.musicone.demo

internal data class MusicAccount(
    val source: MusicSource,
    val userId: String,
    val nickname: String,
    val avatarUrl: String?,
    val hasVipAccess: Boolean = false,
    val backgroundUrl: String? = null,
    val signature: String = "",
    val follows: Int = 0,
    val followers: Int = 0,
)

internal enum class SessionStatus {
    CHECKING,
    SIGNED_OUT,
    CONNECTED,
}

internal enum class PlatformLoginMethod { QR_CODE, SMS }

internal enum class PlatformQrLoginStatus { IDLE, LOADING, WAITING_SCAN, WAITING_CONFIRM, EXPIRED, SUCCESS, FAILED }

internal data class PlatformSettingsUiState(
    val selectedSource: MusicSource = MusicSource.NETEASE,
    val sourceSelected: Boolean = false,
    val loginSource: MusicSource = MusicSource.NETEASE,
    val account: MusicAccount? = null,
    val sessionStatus: SessionStatus = SessionStatus.CHECKING,
    val sessionRevision: Long = 0L,
    val phone: String = "",
    val countryCode: String = "86",
    val captcha: String = "",
    val captchaSent: Boolean = false,
    val resendSeconds: Int = 0,
    val loginMethod: PlatformLoginMethod = PlatformLoginMethod.QR_CODE,
    val qrUrl: String = "",
    val qrImageBase64: String = "",
    val qrStatus: PlatformQrLoginStatus = PlatformQrLoginStatus.IDLE,
    val qrMessage: String? = null,
    val securityVerification: PlatformSecurityVerificationUiState? = null,
    val working: Boolean = false,
    val message: String? = null,
)

internal enum class SettingsDestination {
    CLOSED,
    SETTINGS,
    SOURCES,
    PLATFORM_LOGIN,
    CACHED_MUSIC,
    CACHED_PLAYLIST,
}
