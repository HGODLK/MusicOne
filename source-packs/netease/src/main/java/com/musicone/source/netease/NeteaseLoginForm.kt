package com.musicone.source.netease

/** 手机号实际变化时才失效短信会话；输入法重复提交相同文本不清空验证码。 */
internal fun NeteaseLoginState.resetSms() = copy(
    captcha = "", captchaSent = false, resendSeconds = 0, working = false, message = null,
)

internal fun NeteaseLoginState.changePhone(value: String): NeteaseLoginState {
    val normalized = value.filter(Char::isDigit).take(20)
    return if (normalized == phone) this else resetSms().copy(phone = normalized)
}

internal fun NeteaseLoginState.changeCountry(value: String): NeteaseLoginState {
    val normalized = value.filter(Char::isDigit).take(4)
    return if (normalized == countryCode) this else resetSms().copy(countryCode = normalized)
}
