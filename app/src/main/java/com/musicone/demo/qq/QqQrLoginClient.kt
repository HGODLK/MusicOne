package com.musicone.demo

import android.util.Base64
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID

/** QQ 与微信各自持有独立扫码会话，最终统一换成音乐账号凭据。 */
internal class QqQrLoginClient(
    private val request: (QqQrHttpRequest) -> PlatformHttpResponse = QqQrHttpRequest::execute,
    private val encodeImage: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val credentialClient = QqQrCredentialClient(request)
    private val qqHeaders = mapOf("Referer" to "https://xui.ptlogin2.qq.com/")
    private val wxHeaders = mapOf("Referer" to "https://open.weixin.qq.com/")

    fun createQrCode(method: PlatformLoginMethod, deviceId: String): PlatformQrChallenge = when (method) {
        PlatformLoginMethod.QR_CODE -> createQq(deviceId)
        PlatformLoginMethod.WECHAT_QR_CODE -> createWechat(deviceId)
        PlatformLoginMethod.SMS -> throw PlatformApiException("请选择扫码登录方式")
    }

    fun checkQrCode(challenge: PlatformQrChallenge): PlatformQrResult {
        require(challenge.source == MusicSource.QQ)
        return when (challenge.loginMethod) {
            PlatformLoginMethod.QR_CODE -> checkQq(challenge)
            PlatformLoginMethod.WECHAT_QR_CODE -> checkWechat(challenge)
            PlatformLoginMethod.SMS -> throw PlatformApiException("扫码会话已失效")
        }
    }

    private fun createQq(deviceId: String): PlatformQrChallenge {
        val response = request(QqQrHttpRequest(
            qqQrUrl("https://ssl.ptlogin2.qq.com/ptqrshow", mapOf(
                "appid" to "716027609", "e" to "2", "l" to "M", "s" to "3", "d" to "72", "v" to "4",
                "t" to nowMillis().toString(), "daid" to "383", "pt_3rd_aid" to "100497308",
            )), headers = qqHeaders,
        ))
        val qrsig = response.cookies()["qrsig"]?.takeIf(String::isNotBlank)
            ?: throw PlatformApiException("QQ 未返回二维码会话，请重试")
        return PlatformQrChallenge(
            source = MusicSource.QQ, key = qrsig, imageBase64 = image(response),
            credentialSeed = "qrsig=$qrsig", deviceId = deviceId, scanMessage = "请使用手机 QQ 扫码",
        )
    }

    private fun createWechat(deviceId: String): PlatformQrChallenge {
        val page = request(QqQrHttpRequest(
            qqQrUrl("https://open.weixin.qq.com/connect/qrconnect", mapOf(
                "appid" to "wx48db31d50e334801",
                "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
                "response_type" to "code", "scope" to "snsapi_login", "state" to UUID.randomUUID().toString(),
                "href" to "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect",
            )), headers = wxHeaders,
        ))
        val uuid = Regex("(?:[?&]|&amp;)uuid=([A-Za-z0-9_-]+)").find(page.text)?.groupValues?.get(1)
            ?: Regex("/connect/qrcode/([A-Za-z0-9_-]+)").find(page.text)?.groupValues?.get(1)
            ?: throw PlatformApiException("微信未返回二维码会话，请重试")
        val seed = page.cookies().asCookieHeader()
        val qr = request(QqQrHttpRequest("https://open.weixin.qq.com/connect/qrcode/$uuid", cookie = seed, headers = wxHeaders))
        return PlatformQrChallenge(
            source = MusicSource.QQ, key = uuid, imageBase64 = image(qr),
            credentialSeed = mergePlatformCredentials(seed, qr.cookies().asCookieHeader()),
            loginMethod = PlatformLoginMethod.WECHAT_QR_CODE, deviceId = deviceId, scanMessage = "请使用微信扫码",
        )
    }

    private fun image(response: PlatformHttpResponse): String {
        if (!qqQrImage(response.bytes)) throw PlatformApiException("平台未返回有效的二维码图片，请重试")
        return encodeImage(response.bytes)
    }

    private fun checkQq(challenge: PlatformQrChallenge): PlatformQrResult {
        val response = request(QqQrHttpRequest(
            qqQrUrl("https://ssl.ptlogin2.qq.com/ptqrlogin", mapOf(
                "u1" to "https://graph.qq.com/oauth2.0/login_jump", "ptqrtoken" to qqQrToken(challenge.key).toString(),
                "ptredirect" to "0", "h" to "1", "t" to "1", "g" to "1", "from_ui" to "1", "ptlang" to "2052",
                "action" to "0-0-${nowMillis()}", "js_ver" to "20102616", "js_type" to "1", "pt_uistyle" to "40",
                "aid" to "716027609", "daid" to "383", "pt_3rd_aid" to "100497308", "has_onekey" to "1",
            )), cookie = challenge.credentialSeed, headers = qqHeaders,
        ))
        val callback = parseQqQrCallback(response.text)
        val seed = mergePlatformCredentials(challenge.credentialSeed, response.cookies().asCookieHeader())
        if (callback.code != 0) return pending(qqQrStatus(callback.code, false), seed, "手机 QQ")
        val code = authorizeQq(callback.redirect)
        return success(credentialClient.authorize(challenge.loginMethod, code, challenge.deviceId, challenge.verificationCredential))
    }

    private fun authorizeQq(redirect: String): String {
        val uri = runCatching { URI(redirect) }.getOrNull()
        if (uri?.scheme != "https" || uri.host !in listOf("ssl.ptlogin2.qq.com", "ssl.ptlogin2.graph.qq.com") ||
            uri.path != "/check_sig" || uri.userInfo != null || uri.port !in listOf(-1, 443)) {
            throw PlatformApiException("QQ 扫码授权响应异常")
        }
        val query = qqQrQuery(redirect)
        val uin = query["uin"]?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: throw PlatformApiException("QQ 未返回授权账号")
        val sigx = query["ptsigx"]?.takeIf(String::isNotBlank) ?: throw PlatformApiException("QQ 未返回授权票据")
        val response = request(QqQrHttpRequest(
            qqQrUrl("https://ssl.ptlogin2.graph.qq.com/check_sig", mapOf(
                "uin" to uin, "pttype" to "1", "service" to "ptqrlogin", "nodirect" to "0", "ptsigx" to sigx,
                "s_url" to "https://graph.qq.com/oauth2.0/login_jump", "ptlang" to "2052", "ptredirect" to "100",
                "aid" to "716027609", "daid" to "383", "j_later" to "0", "low_login_hour" to "0",
                "regmaster" to "0", "pt_login_type" to "3", "pt_aid" to "0", "pt_aaid" to "16",
                "pt_light" to "0", "pt_3rd_aid" to "100497308",
            )), headers = qqHeaders,
        ))
        val cookies = response.cookies()
        val pSkey = cookies["p_skey"]?.takeIf(String::isNotBlank) ?: throw PlatformApiException("QQ 授权票据失效，请刷新二维码")
        val authorized = request(QqQrHttpRequest(
            url = "https://graph.qq.com/oauth2.0/authorize", cookie = cookies.asCookieHeader(), headers = qqHeaders,
            form = mapOf(
                "response_type" to "code", "client_id" to "100497308",
                "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
                "scope" to "get_user_info,get_app_friends", "state" to "state", "switch" to "",
                "from_ptlogin" to "1", "src" to "1", "update_auth" to "1", "openapi" to "1010_1030",
                "g_tk" to qqQrToken(pSkey, 5381).toString(), "auth_time" to nowMillis().toString(), "ui" to UUID.randomUUID().toString(),
            ),
        ))
        val location = authorized.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull().orEmpty()
        return qqQrAuthorizationCode(location)
    }

    private fun checkWechat(challenge: PlatformQrChallenge): PlatformQrResult {
        val response = try {
            request(QqQrHttpRequest(
                qqQrUrl("https://lp.open.weixin.qq.com/connect/l/qrconnect", mapOf("uuid" to challenge.key, "_" to nowMillis().toString())),
                headers = wxHeaders,
            ))
        } catch (_: SocketTimeoutException) {
            // 微信长轮询无事件时可能先于服务端结束，继续等待同一二维码。
            return pending(PlatformQrLoginStatus.WAITING_SCAN, challenge.credentialSeed, "微信")
        }
        val status = Regex("window\\.wx_errcode\\s*=\\s*(\\d+)").find(response.text)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw PlatformApiException("微信扫码状态响应异常，请刷新二维码")
        val seed = mergePlatformCredentials(challenge.credentialSeed, response.cookies().asCookieHeader())
        if (status != 405) return pending(qqQrStatus(status, true), seed, "微信")
        val code = Regex("window\\.wx_code\\s*=\\s*['\"]([A-Za-z0-9_-]+)['\"]").find(response.text)?.groupValues?.get(1)
            ?: throw PlatformApiException("微信未返回授权凭据，请刷新二维码")
        return success(credentialClient.authorize(challenge.loginMethod, code, challenge.deviceId, challenge.verificationCredential))
    }

    private fun pending(status: PlatformQrLoginStatus, cookie: String, scanApp: String) =
        PlatformQrResult(status, qqQrStatusMessage(status, scanApp), cookie)

    private fun success(result: QqSmsLoginResult) =
        PlatformQrResult(PlatformQrLoginStatus.SUCCESS, "登录成功", result.cookie, result.account)
}
