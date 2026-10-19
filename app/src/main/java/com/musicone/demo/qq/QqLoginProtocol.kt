package com.musicone.demo

import android.util.Log
import org.json.JSONObject

/** 短信、扫码换票与续期共用稳定的 Android 设备上下文。 */
internal fun qqAndroidLoginComm(deviceId: String, login: Boolean): JSONObject {
    val stableId = deviceId.filter(Char::isLetterOrDigit).take(32).ifBlank { "musiconeandroidclient" }
    return JSONObject()
        .put("ct", 11).put("cv", 14090008).put("v", 14090008).put("chid", "10003505")
        .put("tmeAppID", "qqmusic").put("tmeLoginMethod", 3)
        .put("QIMEI", "").put("QIMEI36", "")
        .put("OpenUDID", stableId).put("OpenUDID2", stableId).put("udid", stableId).put("aid", stableId)
        .put("os_ver", "14").put("phonetype", "MusicOne")
        .put("devicelevel", "34").put("newdevicelevel", "34").put("rom", "MusicOne")
        .also { if (login) it.put("tmeLoginType", 0) }
}

/** 每次请求只携带本次扫码会话的 Cookie，不读取已有账号。 */
internal data class QqQrHttpRequest(
    val url: String,
    val cookie: String = "",
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
    val json: String? = null,
    val followRedirects: Boolean = false,
) {
    fun execute(): PlatformHttpResponse = when {
        form != null -> PlatformHttp.postForm(url, form, cookie, headers, followRedirects)
        json != null -> PlatformHttp.postJson(url, json, cookie, headers)
        else -> PlatformHttp.get(url, cookie, headers, followRedirects)
    }
}

internal fun qqQrUrl(base: String, values: Map<String, String>): String =
    base + "?" + values.entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }

internal class QqQrCredentialClient(
    private val request: (QqQrHttpRequest) -> PlatformHttpResponse = QqQrHttpRequest::execute,
) {
    fun authorize(method: PlatformLoginMethod, code: String, deviceId: String, verificationCredential: String = ""): QqSmsLoginResult {
        val wechat = method == PlatformLoginMethod.WECHAT_QR_CODE
        val comm = if (wechat) {
            qqAndroidLoginComm(deviceId, true).also { it.remove("tmeLoginMethod") }
                .put("tmeLoginType", 1)
        } else {
            // QQ 扫码通过 QQConnect Web 端 OAuth 换票，信封对齐 yqq 网页端规范
            JSONObject()
                .put("g_tk", 5381)
                .put("platform", "yqq")
                .put("ct", 24)
                .put("cv", 0)
        }
        val body = JSONObject()
            .put("comm", comm)
            .put("qrLogin", JSONObject()
                .put("module", if (wechat) "music.login.LoginServer" else "QQConnectLogin.LoginServer")
                .put("method", if (wechat) "Login" else "QQLogin")
                .put("param", JSONObject().put("code", code).also {
                    if (wechat) it.put("strAppid", "wx48db31d50e334801")
                }))
        Log.w("QqQrLogin", "开始换票: body=${body.toString().take(200)}")
        val raw = request(QqQrHttpRequest(
            url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
            json = body.toString(),
            cookie = verificationCredential,
            headers = mapOf("User-Agent" to "QQMusic 14090008(android 14)", "Referer" to "https://y.qq.com/"),
        ))
        Log.w("QqQrLogin", "换票返回: status=${raw.status}, text=${raw.text.take(300)}")
        val root = JSONObject(raw.text)
        if (root.optInt("code", -1) != 0) throw qqLoginError(root, "QQ 音乐扫码登录失败", "登录信息已过期，请刷新二维码")
        val result = root.optJSONObject("qrLogin") ?: throw PlatformApiException("QQ 音乐没有返回扫码登录结果")
        val cookie = mergePlatformCredentials(verificationCredential, raw.cookies().asCookieHeader())
        result.throwIfQqSecurityVerificationRequired(cookie)
        if (result.optInt("code", -1) != 0) throw qqLoginError(result, "QQ 音乐扫码登录失败", "登录信息已过期，请刷新二维码")
        val data = result.optJSONObject("data") ?: throw PlatformApiException("QQ 音乐没有返回登录凭据")
        // 显式保留身份类型，续期时不能把微信身份当作 QQ 身份。
        return data.put("loginType", if (wechat) 1 else 2).toQqSmsLoginResult(cookie)
    }
}
