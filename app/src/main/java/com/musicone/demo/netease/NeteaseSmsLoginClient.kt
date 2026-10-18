package com.musicone.demo

import org.json.JSONObject

internal data class NeteaseSmsRequest(val path: String, val payload: JSONObject, val cookie: String) {
    val url: String get() = "https://interface.music.163.com" + path.replace("/api/", "/eapi/")
    val headers: Map<String, String> get() = mapOf(
        "Accept" to "*/*",
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148",
    )
}

/** 短信匿名会话独立于播放请求；协议对齐 MeloX，不带旧账号或 RN 设备上下文。 */
internal class NeteaseSmsLoginClient(
    private val post: (NeteaseSmsRequest) -> PlatformHttpResponse = { request ->
        PlatformHttp.postForm(request.url, mapOf("params" to NeteaseCrypto.encryptEapi(request.path,
            request.payload.toString())), request.cookie, request.headers)
    },
) {
    fun sendCaptcha(phone: String, countryCode: String, cookie: String): NeteaseCaptchaResult {
        val response = request("/api/sms/captcha/sent", JSONObject().put("cellphone", phone).put("ctcode", countryCode), cookie)
        return NeteaseCaptchaResult(response.first.optString("message").ifBlank { "验证码已发送" }, response.second)
    }

    fun login(phone: String, countryCode: String, captcha: String, cookie: String): String {
        val response = request("/api/w/login/cellphone", JSONObject().put("phone", phone)
            .put("countrycode", countryCode).put("captcha", captcha).put("type", 1).put("remember", true), cookie)
        if (!neteaseHasLoginCookie(response.second)) throw NeteaseApiException("网易云没有返回有效登录凭据")
        return response.second
    }

    private fun request(path: String, data: JSONObject, cookie: String): Pair<JSONObject, String> {
        val response = post(neteaseSmsRequest(path, data, cookie))
        val body = runCatching { JSONObject(response.text) }.getOrElse { throw NeteaseApiException("网易云返回了无法识别的数据") }
        val headers = response.headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
        val merged = neteaseMergeResponseCookies(cookie, body.optString("cookie").split(';') + headers)
        // HTTP 400 也可能携带完整业务错误和挑战，先分类再处理传输状态。
        body.throwIfNeteaseSecurityVerificationRequired(merged)
        if (response.status !in 200..299) throw NeteaseApiException(
            body.optString("message").ifBlank { body.optString("msg").ifBlank { "网易云请求失败（${response.status}）" } },
            body.optInt("code", response.status))
        body.requireSuccess("网易云短信请求失败")
        return body to merged
    }
}

internal fun neteaseSmsRequest(path: String, data: JSONObject, cookie: String, now: Long = System.currentTimeMillis()): NeteaseSmsRequest =
    NeteaseSmsRequest(path, JSONObject(data.toString()).put("e_r", false).put("header", JSONObject()
        .put("os", "ios").put("appver", "9.0.90").put("osver", "18.0")
        .put("buildver", (now / 1000).toString()).put("channel", "distribution")
        .put("requestId", "${now}_0000").put("__csrf", "")), cookie)

internal fun neteaseHasLoginCookie(cookie: String): Boolean = cookie.cookieValues()["MUSIC_U"].isNullOrBlank().not()

internal fun neteaseMergeResponseCookies(cookie: String, headers: List<String>): String {
    val values = cookie.cookieValues().toMutableMap()
    headers.forEach { header ->
        val pair = header.substringBefore(';').trim().split('=', limit = 2)
        if (pair.size == 2 && pair[0].isNotBlank()) {
            if (pair[1].isBlank() || header.split(';').any { it.trim().equals("Max-Age=0", true) }) values.remove(pair[0])
            else values[pair[0]] = pair[1]
        }
    }
    return values.asCookieHeader()
}
