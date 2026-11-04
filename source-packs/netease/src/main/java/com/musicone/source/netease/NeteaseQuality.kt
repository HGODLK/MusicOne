package com.musicone.source.netease

internal enum class NeteaseOrigin(val label: String, val idPrefix: String) { NETEASE("网易云音乐", "netease-") }
internal enum class NeteaseQuality(val label: String, val compactLabel: String, val bitRate: Int, val providerLevel: String) {
    STANDARD("标准音质", "标准", 128_000, "standard"), HIGHER("较高音质", "较高", 192_000, "higher"),
    EXHIGH("极高音质", "极高", 320_000, "exhigh"), LOSSLESS("无损音质", "无损", 350_000, "lossless"),
    HI_RES("Hi-Res", "Hi-Res", 999_000, "hires"), DOLBY("Dolby全景", "Dolby", 999_000, "dolby")
}
internal val NETEASE_AUDIO_QUALITIES = listOf(NeteaseQuality.STANDARD, NeteaseQuality.EXHIGH, NeteaseQuality.LOSSLESS, NeteaseQuality.HI_RES)
internal data class NeteasePlaybackAddress(val url: String, val requestedQuality: NeteaseQuality,
    val actualQuality: NeteaseQuality, val bitRate: Int, val format: String, val trial: Boolean,
    val actualFormat: String? = null, val verificationPending: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(), val cacheKey: String? = null, val mimeType: String? = null)
internal class NeteaseProtocolException(message: String, val code: Int? = null) : Exception(message)
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) }

internal data class NeteaseAccount(val source: NeteaseOrigin = NeteaseOrigin.NETEASE, val userId: String,
    val nickname: String, val avatarUrl: String?, val hasVipAccess: Boolean = false, val backgroundUrl: String? = null,
    val signature: String = "", val follows: Int = 0, val followers: Int = 0,
    val confirmedMusicMembership: Boolean? = null)
internal enum class NeteaseLoginMethod { QR_CODE, SMS }
internal enum class NeteaseQrLoginStatus { IDLE, LOADING, WAITING_SCAN, WAITING_CONFIRM, EXPIRED, SUCCESS, FAILED }
internal data class NeteaseLoginState(val loginSource: NeteaseOrigin = NeteaseOrigin.NETEASE,
    val phone: String = "", val countryCode: String = "86", val captcha: String = "", val captchaSent: Boolean = false,
    val resendSeconds: Int = 0, val loginMethod: NeteaseLoginMethod = NeteaseLoginMethod.QR_CODE,
    val qrUrl: String = "", val qrImageBase64: String = "", val qrStatus: NeteaseQrLoginStatus = NeteaseQrLoginStatus.IDLE,
    val qrMessage: String? = null, val working: Boolean = false, val message: String? = null)
internal data class NeteaseLoginActions(val onBack: () -> Unit, val onStartQrLogin: () -> Unit,
    val onStopQrLogin: () -> Unit, val onSelectLoginMethod: (NeteaseLoginMethod) -> Unit,
    val onCountryCodeChange: (String) -> Unit, val onPhoneChange: (String) -> Unit, val onCaptchaChange: (String) -> Unit,
    val onSendCaptcha: () -> Unit, val onLogin: () -> Unit)
internal data class NeteaseCatalogState(val libraryPlaylists: List<NeteasePlaylist> = emptyList(),
    val loadingLibrary: Boolean = false, val libraryMessage: String? = null)
