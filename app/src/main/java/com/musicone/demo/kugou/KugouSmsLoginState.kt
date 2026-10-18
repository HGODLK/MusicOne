package com.musicone.demo

import org.json.JSONObject

internal class KugouSmsRequestException(
    message: String,
    apiCode: Int?,
    val credentialSeed: String,
) : PlatformApiException(message, apiCode)

/** 补齐设备字段时保留短信发送和安全验证返回的 Cookie。 */
internal fun kugouLoginDeviceCookie(existingCredential: String): String {
    val values = existingCredential.cookieValues().toMutableMap()
    val required = listOf("KUGOU_API_GUID", "KUGOU_API_MID", "KUGOU_API_MAC", "KUGOU_API_DEV")
    if (required.any { values[it].isNullOrBlank() }) {
        val generated = KugouProtocol.createDeviceCookie().cookieValues()
        required.forEach { name ->
            if (values[name].isNullOrBlank()) values[name] = generated.getValue(name)
        }
    }
    if (values["dfid"].isNullOrBlank()) values["dfid"] = KugouProtocol.randomDfid()
    return values.asCookieHeader()
}

internal fun JSONObject.requireKugouSmsSuccess(fallback: String, credentialSeed: String) {
    val errorCode = optInt("error_code", optInt("errcode", 0))
    if (optInt("status", -1) == 1 && errorCode == 0) return
    val message = when (errorCode) {
        34_175 -> "此手机号关联多个酷狗账号，请使用扫码登录"
        34_182 -> "该手机号未绑定酷狗音乐"
        else -> kugouLoginMessage().ifBlank {
            errorCode.takeIf { it != 0 }?.let { "$fallback（$it）" } ?: fallback
        }
    }
    throw KugouSmsRequestException(message, errorCode.takeIf { it != 0 }, credentialSeed)
}

internal fun JSONObject.kugouLoginMessage(): String = optString("error").ifBlank {
    optString("message").ifBlank { optString("msg") }
}
