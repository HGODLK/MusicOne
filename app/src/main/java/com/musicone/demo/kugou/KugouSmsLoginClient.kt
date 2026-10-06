package com.musicone.demo

import org.json.JSONObject

internal data class KugouCaptchaResult(
    val message: String,
    val deviceCookie: String,
)

internal data class KugouSmsLoginResult(
    val cookie: String,
    val account: MusicAccount,
)

internal class KugouSmsLoginClient {
    fun sendCaptcha(phone: String, countryCode: String, existingCredential: String): KugouCaptchaResult {
        val normalizedPhone = normalizeKugouPhone(phone, countryCode)
        val deviceCookie = kugouLoginDeviceCookie(existingCredential)
        val body = JSONObject()
            .put("businessid", 5)
            .put("mobile", normalizedPhone)
            .put("plat", 3)
        val response = postAndroid(SEND_CODE_API, body, deviceCookie)
        response.securityChallenge(response.cookie)?.let { throw PlatformSecurityVerificationRequired(it) }
        response.body.requireKugouLoginSuccess("酷狗音乐验证码发送失败")
        return KugouCaptchaResult(
            response.body.kugouLoginMessage().ifBlank { "验证码已发送" },
            response.cookie,
        )
    }

    fun loginByCaptcha(
        phone: String,
        countryCode: String,
        captcha: String,
        deviceCookie: String,
    ): KugouSmsLoginResult {
        val normalizedPhone = normalizeKugouPhone(phone, countryCode)
        val normalizedCaptcha = captcha.trim()
        if (normalizedCaptcha.length !in 4..10 || normalizedCaptcha.any { !it.isDigit() }) {
            throw PlatformApiException("请输入正确的验证码")
        }
        val stableCookie = kugouLoginDeviceCookie(deviceCookie)
        val device = stableCookie.cookieValues()
        val timestamp = System.currentTimeMillis()
        val encrypted = KugouLoginCrypto.encryptCredential(
            JSONObject().put("mobile", normalizedPhone).put("code", normalizedCaptcha).toString(),
        )
        val body = JSONObject()
            .put("plat", 1)
            .put("support_multi", 1)
            .put("t1", KugouLoginCrypto.encryptFixed("|$timestamp", T1_KEY, T1_IV))
            .put("t2", KugouLoginCrypto.encryptFixed(
                "${device["KUGOU_API_GUID"]}|$LOGIN_DEVICE_SALT|${device["KUGOU_API_MAC"]}|${device["KUGOU_API_DEV"]}|$timestamp",
                T2_KEY,
                T2_IV,
            ))
            .put("clienttime_ms", timestamp)
            .put("mobile", normalizedPhone.kugouMaskedPhone())
            .put("key", KugouProtocol.loginKey(timestamp))
            .put("pk", KugouLoginCrypto.rawRsaEncrypt(
                JSONObject().put("clienttime_ms", timestamp).put("key", encrypted.key).toString(),
            ))
            .put("params", encrypted.ciphertext)
            .put("dfid", device["dfid"])
            .put("dev", device["KUGOU_API_DEV"])
            .put("gitversion", "5f0b7c4")
        val response = postAndroid(LOGIN_API, body, stableCookie, loginRequest = true)
        response.securityChallenge(response.cookie)?.let { throw PlatformSecurityVerificationRequired(it) }
        response.body.requireKugouLoginSuccess("酷狗音乐登录失败")
        val data = response.body.optJSONObject("data") ?: throw PlatformApiException("酷狗音乐没有返回登录结果")
        data.optString("secu_params").takeIf(String::isNotBlank)?.let { secureValue ->
            val decrypted = runCatching { KugouLoginCrypto.decryptCredential(secureValue, encrypted.key) }
                .getOrElse { throw PlatformApiException("酷狗音乐登录凭据解析失败") }
            runCatching { JSONObject(decrypted) }
                .onSuccess(data::copyFrom)
                .onFailure { data.put("token", decrypted.trim().trim('"')) }
        }
        return data.toKugouSmsLoginResult(response.cookie)
    }

    fun completeSecurityVerification(
        challenge: PlatformSecurityChallenge,
        result: PlatformSecurityVerificationResult,
    ): String {
        val cookie = mergePlatformCredentials(challenge.credentialSeed, result.cookie)
        if (challenge.kind == PlatformSecurityVerificationKind.WEB || challenge.eventId.isBlank()) return cookie
        val session = cookie.requiredKugouDeviceSession()
        val proof = KugouBehaviorProofFactory.create(session, result)
        val verificationValue = when (challenge.kind) {
            PlatformSecurityVerificationKind.TENCENT_CAPTCHA ->
                result.payload.takeIf { it.startsWith("KGCodeTX|") } ?: "KGCodeTX|${result.payload}"
            PlatformSecurityVerificationKind.SMS -> result.payload
            PlatformSecurityVerificationKind.WEB -> result.payload
            PlatformSecurityVerificationKind.MANUAL -> result.payload
        }
        if (verificationValue.isBlank()) throw PlatformApiException("请先完成安全验证")
        val encryptedPayload = if (challenge.kind == PlatformSecurityVerificationKind.SMS) {
            JSONObject().put("code", verificationValue)
        } else {
            JSONObject()
        }
        val encrypted = KugouLoginCrypto.encryptCredential(encryptedPayload.toString())
        val body = JSONObject()
            .put("eventid", challenge.eventId)
            .put("userid", session.userId.toLongOrNull() ?: 0L)
            .put("platid", 2)
            .put("v_type", challenge.verificationType)
            .put("wasm", 1)
            .put("i", "")
            .put("sid", proof.sid)
            .put("edt", proof.edt)
            .put("pk", KugouLoginCrypto.rawRsaEncrypt(JSONObject().put("key", encrypted.key).toString()))
            .put("params", encrypted.ciphertext)
        if (challenge.kind == PlatformSecurityVerificationKind.SMS) {
            body.put("code", verificationValue)
        } else {
            body.put("verifycode", verificationValue)
        }
        val response = postAndroid(
            VERIFY_USER_API,
            body,
            cookie,
            loginRequest = true,
            clientVersion = VERIFY_CLIENT_VERSION,
        )
        response.body.requireKugouLoginSuccess("酷狗音乐安全验证失败")
        return response.cookie
    }

    private fun postAndroid(
        baseUrl: String,
        body: JSONObject,
        cookie: String,
        loginRequest: Boolean = false,
        clientVersion: String = KugouProtocol.clientVersion,
    ): KugouLoginHttpResult {
        val cookies = cookie.cookieValues()
        val params = linkedMapOf(
            "dfid" to cookies["dfid"].orEmpty().ifBlank { "-" },
            "mid" to cookies["KUGOU_API_MID"].orEmpty(),
            "uuid" to "-",
            "appid" to KugouProtocol.appId,
            "clientver" to clientVersion,
            "clienttime" to (System.currentTimeMillis() / 1_000).toString(),
        )
        val bodyText = body.toString()
        val headers = kugouAndroidHeaders(params, null) + if (loginRequest) {
            mapOf("support-calm" to "1", "User-Agent" to LOGIN_USER_AGENT)
        } else {
            mapOf("User-Agent" to LOGIN_USER_AGENT)
        }
        val response = PlatformHttp.postJson(
            KugouProtocol.signedAndroidUrl(baseUrl, params, bodyText),
            bodyText,
            cookie,
            headers,
        )
        val mergedCookie = (cookie.cookieValues() + response.cookies()).asCookieHeader()
        return KugouLoginHttpResult(JSONObject(response.text), mergedCookie, response.headers)
    }

    private fun KugouLoginHttpResult.securityChallenge(deviceCookie: String): PlatformSecurityChallenge? {
        val errorCode = body.optInt("error_code", body.optInt("errcode", 0))
        val eventId = headers.entries.firstOrNull { it.key.equals("ssa-code", ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
            .ifBlank { body.optString("ssaCode") }
            .ifBlank { body.optString("eventid") }
        if (errorCode != 20_028 && eventId.isBlank()) return null
        if (eventId.isBlank()) {
            return PlatformSecurityChallenge(
                source = MusicSource.KUGOU,
                kind = PlatformSecurityVerificationKind.WEB,
                title = "酷狗音乐安全验证",
                message = "请完成酷狗音乐账号安全验证",
                url = "https://www.kugou.com/",
                credentialSeed = deviceCookie,
            )
        }
        val infoBody = JSONObject()
            .put("eventid", eventId)
            .put("userid", 0)
            .put("platid", 2)
            .put("rtype", 1)
            .put("wasm", 1)
            .put("i", "")
            .put("sid", "")
            .put("edt", "")
        val infoResponse = postAndroid(VERIFY_INFO_API, infoBody, deviceCookie)
        infoResponse.body.requireKugouLoginSuccess("酷狗音乐安全验证信息获取失败")
        val data = infoResponse.body.optJSONObject("data") ?: JSONObject()
        val verificationType = data.optInt("v_type", 23)
        val appId = data.opt("txappid")?.toString().orEmpty()
        val directUrl = data.optString("url").substringAfter('|').takeIf { it.startsWith("http") }.orEmpty()
        val kind = when {
            verificationType == 23 && appId.isNotBlank() -> PlatformSecurityVerificationKind.TENCENT_CAPTCHA
            verificationType == 32 -> PlatformSecurityVerificationKind.SMS
            else -> PlatformSecurityVerificationKind.WEB
        }
        return PlatformSecurityChallenge(
            source = MusicSource.KUGOU,
            kind = kind,
            title = "酷狗音乐安全验证",
            message = if (kind == PlatformSecurityVerificationKind.SMS) "请输入平台发送的安全验证码" else "请拖动滑块或完成点选验证",
            url = directUrl.ifBlank { if (kind == PlatformSecurityVerificationKind.WEB) "https://www.kugou.com/" else "" },
            credentialSeed = infoResponse.cookie,
            eventId = eventId,
            verificationType = verificationType,
            appId = appId,
        )
    }

    companion object {
        private const val SEND_CODE_API = "https://login-user.kugou.com/v7/send_mobile_code"
        private const val LOGIN_API = "https://loginserviceretry.kugou.com/v7/login_by_verifycode"
        private const val VERIFY_INFO_API = "https://gateway.kugou.com/verifyservice/v3/get_verify_info"
        private const val VERIFY_USER_API = "https://verifyservice.kugou.com/v4/verify_user_info"
        private const val VERIFY_CLIENT_VERSION = "11510"
        private const val LOGIN_USER_AGENT = "Android16-1070-11440-130-0-LOGIN-wifi"
        private const val LOGIN_DEVICE_SALT = "0f607264fc6318a92b9e13c65db7cd3c"
        private const val T2_KEY = "fd14b35e3f81af3817a20ae7adae7020"
        private const val T2_IV = "17a20ae7adae7020"
        private const val T1_KEY = "5e4ef500e9597fe004bd09a46d8add98"
        private const val T1_IV = "04bd09a46d8add98"
    }
}

private data class KugouLoginHttpResult(
    val body: JSONObject,
    val cookie: String,
    val headers: Map<String, List<String>>,
)

private fun normalizeKugouPhone(phone: String, countryCode: String): String {
    val normalizedCountryCode = countryCode.trim().removePrefix("+").ifBlank { "86" }
    if (normalizedCountryCode != "86") throw PlatformApiException("酷狗音乐短信登录当前仅支持 +86 手机号")
    return phone.trim().also { value ->
        if (value.length != 11 || value.any { !it.isDigit() }) throw PlatformApiException("请输入正确的 11 位手机号码")
    }
}

private fun kugouLoginDeviceCookie(existingCredential: String): String {
    val required = listOf("KUGOU_API_GUID", "KUGOU_API_MID", "KUGOU_API_MAC", "KUGOU_API_DEV")
    val existing = existingCredential.cookieValues()
    val device = if (required.all { existing[it].isNullOrBlank().not() }) {
        existing.filterKeys { it in required || it == "dfid" }
    } else {
        KugouProtocol.createDeviceCookie().cookieValues()
    }.toMutableMap()
    if (device["dfid"].isNullOrBlank()) device["dfid"] = KugouProtocol.randomDfid()
    return device.asCookieHeader()
}

private fun String.kugouMaskedPhone(): String = "${take(2)}*****${takeLast(1)}"

private fun JSONObject.requireKugouLoginSuccess(fallback: String) {
    val status = optInt("status", 1)
    val errorCode = optInt("error_code", optInt("errcode", 0))
    if (status == 0 || errorCode != 0) {
        val code = errorCode.takeIf { it != 0 }
        throw PlatformApiException(kugouLoginMessage().ifBlank { code?.let { "$fallback（$it）" } ?: fallback }, code)
    }
}

private fun JSONObject.kugouLoginMessage(): String = optString("error").ifBlank {
    optString("message").ifBlank { optString("msg") }
}

private fun JSONObject.copyFrom(other: JSONObject) {
    other.keys().forEach { key -> put(key, other.opt(key)) }
}

private fun JSONObject.toKugouSmsLoginResult(baseCookie: String): KugouSmsLoginResult {
    val userId = opt("userid")?.toString().orEmpty()
    val token = optString("token")
    if (userId.isBlank() || userId == "0" || token.isBlank()) throw PlatformApiException("酷狗音乐没有返回登录凭据")
    val values = baseCookie.cookieValues().toMutableMap()
    listOf("t1", "token", "userid", "vip_type", "vip_token").forEach { name ->
        if (has(name) && !isNull(name)) values[name] = opt(name)?.toString().orEmpty()
    }
    values["token"] = token
    values["userid"] = userId
    val nickname = listOf("nickname", "username", "nick_name")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }
        ?: "酷狗用户 $userId"
    val avatar = listOf("pic", "userpic", "avatar", "headimgurl", "img")
        .firstNotNullOfOrNull { name -> optString(name).takeIf(String::isNotBlank) }
        ?.let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") }
    values["nickname"] = nickname
    avatar?.let { values["avatar"] = it }
    return KugouSmsLoginResult(
        values.asCookieHeader(),
        MusicAccount(MusicSource.KUGOU, userId, nickname, avatar),
    )
}

private fun String.requiredKugouDeviceSession(): KugouSession {
    val values = cookieValues()
    val mid = values["KUGOU_API_MID"].orEmpty()
    if (mid.isBlank()) throw PlatformApiException("酷狗音乐设备信息已失效")
    return KugouSession(
        cookie = this,
        token = values["token"].orEmpty(),
        userId = values["userid"].orEmpty().ifBlank { "0" },
        mid = mid,
        dfid = values["dfid"].orEmpty().ifBlank(KugouProtocol::randomDfid),
    )
}
