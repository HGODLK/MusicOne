package com.musicone.source.qq

import com.musicone.source.qq.internal.PlatformLoginMethod
import com.musicone.source.qq.internal.PlatformQrLoginStatus

/** 沿用 0.2.0 登录页的展示状态，不向宿主暴露 QQ 认证流程。 */
internal data class QqLoginUiState(
    val loginMethod: PlatformLoginMethod = PlatformLoginMethod.SMS,
    val countryCode: String = "86",
    val phone: String = "",
    val captcha: String = "",
    val captchaSent: Boolean = false,
    val resendSeconds: Int = 0,
    val working: Boolean = false,
    val message: String? = null,
    val qrStatus: PlatformQrLoginStatus = PlatformQrLoginStatus.IDLE,
    val qrMessage: String? = null,
    val qrImageBase64: String = "",
    val qrUrl: String = "",
) {
    fun changePhone(value: String): QqLoginUiState {
        val normalized = value.filter(Char::isDigit).take(20)
        return if (normalized == phone) this else copy(phone = normalized,
            captcha = "", captchaSent = false, resendSeconds = 0, message = null)
    }
    fun changeCountry(value: String): QqLoginUiState {
        val normalized = value.filter(Char::isDigit).take(4)
        return if (normalized == countryCode) this else copy(countryCode = normalized,
            captcha = "", captchaSent = false, resendSeconds = 0, message = null)
    }
    val canSendCaptcha get() = !working && resendSeconds == 0 && phone.length >= 6 && countryCode.isNotBlank()
    val canLogin get() = !working && phone.length >= 6 && captcha.length >= 4 && captchaSent
}

internal data class QqLoginActions(
    val onSelectLoginMethod: (PlatformLoginMethod) -> Unit,
    val onCountryCodeChange: (String) -> Unit,
    val onPhoneChange: (String) -> Unit,
    val onCaptchaChange: (String) -> Unit,
    val onSendCaptcha: () -> Unit,
    val onLogin: () -> Unit,
    val onStartQrLogin: () -> Unit,
)
