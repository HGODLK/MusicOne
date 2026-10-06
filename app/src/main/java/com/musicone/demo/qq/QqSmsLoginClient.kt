package com.musicone.demo

import org.json.JSONObject

internal data class QqSmsLoginResult(
    val cookie: String,
    val account: MusicAccount,
)

internal data class QqCaptchaResult(
    val message: String,
    val cookie: String,
)

internal class QqSmsLoginClient {
    fun refreshCredential(deviceId: String, cookie: String): String {
        val accountId = qqCredentialAccountId(cookie)
        if (accountId.isBlank() || qqCredentialMusicKey(cookie).isBlank()) {
            throw PlatformApiException("QQ 音乐缺少可续期的登录凭据", 301)
        }
        val loginType = cookie.cookieValues()["loginType"]?.toIntOrNull()?.takeIf { it == 1 || it == 2 }
            ?: if (qqCredentialMusicKey(cookie).startsWith("W_X")) 1 else 2
        val response = request(
            method = "Login",
            param = qqCredentialRefreshParam(cookie),
            deviceId = deviceId,
            login = true,
            cookie = cookie,
            loginType = loginType,
        )
        response.body.throwIfQqSecurityVerificationRequired(response.cookie)
        if (response.body.optInt("code", 0) != 0) {
            throw qqLoginError(response.body, "QQ 音乐登录续期失败")
        }
        val data = response.body.optJSONObject("data")
            ?: throw PlatformApiException("QQ 音乐没有返回续期凭据")
        val newKey = listOf("musickey", "music_key", "qqmusic_key", "qm_keyst", "strMusicKey")
            .firstNotNullOfOrNull { data.optString(it).takeIf(String::isNotBlank) }
            ?: throw PlatformApiException("QQ 音乐没有返回新的登录凭据")
        val returnedId = listOf("musicid", "str_musicid", "musicId", "userid", "user_id", "uin")
            .firstNotNullOfOrNull { name ->
                data.opt(name)?.toString()?.removePrefix("o")?.trimStart('0')
                    ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            }
        if (returnedId != null && returnedId != accountId) {
            throw PlatformApiException("QQ 音乐续期返回了其他账号")
        }
        if (returnedId == null) data.put("musicid", accountId)
        val refreshed = data.toQqSmsLoginResult(response.cookie).cookie
        if (qqCredentialMusicKey(refreshed) != newKey) {
            throw PlatformApiException("QQ 音乐续期凭据不完整")
        }
        return refreshed
    }

    fun sendCaptcha(phone: String, countryCode: String, deviceId: String, cookie: String): QqCaptchaResult {
        val normalizedPhone = phone.normalizedPhone()
        val normalizedCountryCode = countryCode.normalizedCountryCode()
        val response = request(
            method = "SendPhoneAuthCode",
            param = JSONObject()
                .put("tmeAppid", "qqmusic")
                .put("areaCode", normalizedCountryCode)
                .put("phoneNo", normalizedPhone),
            deviceId = deviceId,
            login = false,
            cookie = cookie,
        )
        response.body.throwIfQqSecurityVerificationRequired(response.cookie)
        return when (val code = response.body.optInt("code", 0)) {
            0 -> QqCaptchaResult("验证码已发送", response.cookie)
            20276 -> {
                throw PlatformApiException("QQ 音乐需要完成安全验证", code)
            }
            100001 -> throw PlatformApiException("请求过于频繁，请稍后再试", code)
            else -> throw qqLoginError(response.body, "QQ 音乐验证码发送失败")
        }
    }

    fun loginByCaptcha(phone: String, captcha: String, deviceId: String, cookie: String): QqSmsLoginResult {
        val normalizedCaptcha = captcha.trim()
        if (normalizedCaptcha.length !in 4..10 || normalizedCaptcha.any { !it.isDigit() }) {
            throw PlatformApiException("请输入正确的验证码")
        }
        val response = request(
            method = "Login",
            param = JSONObject()
                .put("phoneNo", phone.normalizedPhone())
                .put("code", normalizedCaptcha)
                .put("loginMode", 1),
            deviceId = deviceId,
            login = true,
            cookie = cookie,
        )
        response.body.throwIfQqSecurityVerificationRequired(response.cookie)
        val code = response.body.optInt("code", 0)
        if (code != 0) throw qqLoginError(response.body, "QQ 音乐登录失败")
        return response.body.optJSONObject("data").orEmptyObject().toQqSmsLoginResult(response.cookie)
    }

    private fun request(
        method: String,
        param: JSONObject,
        deviceId: String,
        login: Boolean,
        cookie: String,
        loginType: Int = 0,
    ): QqLoginResponse {
        val requestKey = "phoneLogin"
        val body = JSONObject()
            .put("comm", qqAndroidLoginComm(deviceId, login).also { comm ->
                if (login) comm.put("tmeLoginType", loginType)
                if (loginType != 0) {
                    comm.put("qq", qqCredentialAccountId(cookie))
                    comm.put("authst", qqCredentialMusicKey(cookie))
                }
            })
            .put(requestKey, JSONObject()
                .put("module", "music.login.LoginServer")
                .put("method", method)
                .put("param", param))
        val raw = PlatformHttp.postJson(
            MUSIC_U_API,
            body.toString(),
            cookie = cookie,
            headers = mapOf(
                "User-Agent" to "QQMusic 14090008(android 14)",
                "Referer" to "https://y.qq.com/",
            ),
        )
        val json = JSONObject(raw.text)
        val topCode = json.optInt("code", 0)
        if (topCode != 0) throw PlatformApiException(
            json.optString("message").ifBlank { "QQ 音乐请求失败（$topCode）" },
            topCode,
        )
        val responseBody = json.optJSONObject(requestKey) ?: throw PlatformApiException("QQ 音乐没有返回登录结果")
        return QqLoginResponse(responseBody, mergePlatformCredentials(cookie, raw.cookies().asCookieHeader()))
    }

    private fun qqAndroidLoginComm(deviceId: String, login: Boolean): JSONObject {
        val stableId = deviceId.filter(Char::isLetterOrDigit).take(32).ifBlank { "musiconeandroidclient" }
        return JSONObject()
            .put("ct", 11)
            .put("cv", 14090008)
            .put("v", 14090008)
            .put("chid", "10003505")
            .put("tmeAppID", "qqmusic")
            .put("tmeLoginMethod", 3)
            .put("QIMEI", "")
            .put("QIMEI36", "")
            .put("OpenUDID", stableId)
            .put("OpenUDID2", stableId)
            .put("udid", stableId)
            .put("aid", stableId)
            .put("os_ver", "14")
            .put("phonetype", "MusicOne")
            .put("devicelevel", "34")
            .put("newdevicelevel", "34")
            .put("rom", "MusicOne")
            .also { if (login) it.put("tmeLoginType", 0) }
    }

    companion object {
        private const val MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    }
}

internal fun qqCredentialRefreshParam(cookie: String): JSONObject {
    val values = cookie.cookieValues()
    val accountId = qqCredentialAccountId(cookie)
    val musicKey = qqCredentialMusicKey(cookie)
    return JSONObject()
        .put("openid", values["openid"].orEmpty())
        .put("access_token", values["access_token"].orEmpty())
        .put("refresh_token", values["refresh_token"].orEmpty())
        .put("expired_in", values["expired_at"]?.toLongOrNull() ?: 0L)
        .put("str_musicid", values["str_musicid"].orEmpty().ifBlank { accountId })
        .put("musicid", accountId.toLongOrNull() ?: 0L)
        .put("musickey", musicKey)
        .put("unionid", values["unionid"].orEmpty())
        .put("refresh_key", values["refresh_key"].orEmpty())
        .put("loginMode", 2)
}

private data class QqLoginResponse(val body: JSONObject, val cookie: String)

internal fun JSONObject.toQqSmsLoginResult(baseCookie: String): QqSmsLoginResult {
    val userId = listOf("musicid", "str_musicid", "musicId", "userid", "user_id", "uin")
        .firstNotNullOfOrNull { name -> opt(name)?.toString()?.takeIf { it.isNotBlank() && it != "0" } }
        ?: throw PlatformApiException("QQ 音乐没有返回账号标识")
    val musicKey = listOf("musickey", "music_key", "qqmusic_key", "qm_keyst", "strMusicKey")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }.orEmpty()
    if (musicKey.isBlank()) throw PlatformApiException("QQ 音乐没有返回登录凭据")
    val credentialNames = listOf(
        "openid", "refresh_token", "access_token", "expired_at", "musicid", "str_musicid",
        "musickey", "unionid", "refresh_key", "musickeyCreateTime", "keyExpiresIn",
        "first_login", "bindAccountType", "needRefreshKeyIn", "encryptUin", "loginType",
    )
    val values = linkedMapOf<String, String>()
    credentialNames.forEach { name ->
        if (has(name) && !isNull(name)) opt(name)?.toString()?.takeIf(String::isNotBlank)?.let { values[name] = it }
    }
    values["musicid"] = userId
    values["musickey"] = musicKey
    values["uin"] = "o" + userId.removePrefix("o").padStart(10, '0')
    values["qqmusic_uin"] = userId.removePrefix("o")
    values["qqmusic_key"] = musicKey
    values["qm_keyst"] = musicKey
    values["authst"] = musicKey
    val cookie = mergePlatformCredentials(baseCookie, values.asCookieHeader())
    val nickname = listOf("nick", "nickname", "nickName", "name")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }
        ?: "QQ 用户 $userId"
    val avatar = listOf("avatar", "avatarUrl", "head", "headPic", "headurl")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }
        ?.let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") }
    return QqSmsLoginResult(cookie, MusicAccount(MusicSource.QQ, userId, nickname, avatar))
}

private fun JSONObject.throwIfQqSecurityVerificationRequired(cookie: String) {
    val code = optInt("code", 0)
    val securityUrl = optJSONObject("data")?.optString("securityURL").orEmpty()
        .ifBlank { optJSONObject("data")?.optString("securityUrl").orEmpty() }
    if (code == 20276 && securityUrl.isNotBlank()) {
        throw PlatformSecurityVerificationRequired(
            PlatformSecurityChallenge(
                source = MusicSource.QQ,
                kind = PlatformSecurityVerificationKind.WEB,
                title = "QQ 音乐安全验证",
                message = "请在页面中拖动滑块或完成点选验证",
                url = securityUrl,
                credentialSeed = cookie,
            ),
        )
    }
}

private fun qqLoginError(response: JSONObject, fallback: String): PlatformApiException {
    val code = response.optInt("code", -1)
    val known = when (code) {
        1000, 104400, 104401 -> "登录信息已过期，请重新获取验证码"
        20261 -> "登录参数错误"
        20271 -> "验证码错误"
        20272 -> "账号绑定异常"
        20274 -> "账号尚未绑定 QQ 音乐"
        20277, 20278, 20450 -> "账号当前无法登录"
        20279 -> "登录设备数量已达上限"
        104604 -> "请求过于频繁，请稍后再试"
        else -> ""
    }
    val message = known.ifBlank {
        response.optString("message").ifBlank {
            response.optString("msg").ifBlank { if (code > 0) "$fallback（$code）" else fallback }
        }
    }
    return PlatformApiException(message, code.takeIf { it > 0 })
}

private fun String.normalizedPhone(): String = trim().also { value ->
    if (value.length !in 6..20 || value.any { !it.isDigit() }) throw PlatformApiException("请输入正确的手机号码")
}

private fun String.normalizedCountryCode(): String = trim().removePrefix("+").ifBlank { "86" }.also { value ->
    if (value.length !in 1..4 || value.any { !it.isDigit() }) throw PlatformApiException("请输入正确的国家或地区代码")
}

private fun JSONObject?.orEmptyObject(): JSONObject = this ?: JSONObject()
