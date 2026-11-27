package com.musicone.source.netease

/** 只有手机号真的变了才重置验证码状态，避免输入法重复回调相同文本把验证码清空。 */
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
