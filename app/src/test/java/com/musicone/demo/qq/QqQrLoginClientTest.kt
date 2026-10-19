package com.musicone.demo

import java.net.SocketTimeoutException
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqQrLoginClientTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private fun response(text: String = "", headers: Map<String, List<String>> = emptyMap()) =
        PlatformHttpResponse(200, text.toByteArray(), headers)
    private fun client(request: (QqQrHttpRequest) -> PlatformHttpResponse) =
        QqQrLoginClient(request, { Base64.getEncoder().encodeToString(it) }, { 1_700_000_000_000 })
    private fun challenge(method: PlatformLoginMethod = PlatformLoginMethod.QR_CODE) = PlatformQrChallenge(
        MusicSource.QQ, "session", credentialSeed = "qrsig=session", loginMethod = method, deviceId = "device",
    )
    private fun credential() = response("""{"code":0,"qrLogin":{"code":0,"data":{
        "musicid":123456,"musickey":"music-key","openid":"open-id","refresh_key":"refresh-key",
        "refresh_token":"refresh-token","nick":"测试账号","musickeyCreateTime":1000,"keyExpiresIn":3600}}}""")

    @Test
    fun QQ扫码完整链路换成音乐凭据并保留刷新字段() {
        val calls = mutableListOf<QqQrHttpRequest>()
        val api = client { req ->
            calls += req
            when (calls.size) {
                1 -> PlatformHttpResponse(200, png, mapOf("Set-Cookie" to listOf("qrsig=session; Path=/")))
                2 -> response("ptuiCB('0','0','https://ssl.ptlogin2.qq.com/check_sig?uin=123456&ptsigx=ticket&s_url=next','0','登录成功','昵称');")
                3 -> response(headers = mapOf("Set-Cookie" to listOf("p_skey=qq-ticket; Domain=graph.qq.com", "p_uin=o123456")))
                4 -> response(headers = mapOf("location" to listOf("https://y.qq.com/portal/wx_redirect.html?login_type=1&code=auth-code&state=state")))
                5 -> credential()
                else -> error("请求次数异常")
            }
        }
        val qr = api.createQrCode(PlatformLoginMethod.QR_CODE, "device")
        assertEquals("session", qr.key)
        assertEquals(Base64.getEncoder().encodeToString(png), qr.imageBase64)
        assertEquals("请使用手机 QQ 扫码", qr.scanMessage)
        val result = api.checkQrCode(qr)
        assertEquals(PlatformQrLoginStatus.SUCCESS, result.status)
        assertEquals("123456", result.account!!.userId)
        val values = result.credential.cookieValues()
        assertEquals("music-key", values["musickey"])
        assertEquals("refresh-key", values["refresh_key"])
        assertEquals("refresh-token", values["refresh_token"])
        assertEquals("2", values["loginType"])
        assertEquals("1000", values["musickeyCreateTime"])
        assertFalse(values.containsKey("qrsig"))
        assertFalse(values.containsKey("p_skey"))
        assertEquals("qrsig=session", calls[1].cookie)
        assertEquals("qq-ticket", calls[3].cookie.cookieValues()["p_skey"])
        assertEquals(qqQrToken("qq-ticket", 5381).toString(), calls[3].form!!["g_tk"])
        assertFalse(calls[3].followRedirects)
        val body = JSONObject(calls[4].json!!)
        assertEquals("QQConnectLogin.LoginServer", body.getJSONObject("qrLogin").getString("module"))
        assertEquals("QQLogin", body.getJSONObject("qrLogin").getString("method"))
        assertEquals("auth-code", body.getJSONObject("qrLogin").getJSONObject("param").getString("code"))
        assertEquals(2, body.getJSONObject("comm").getInt("tmeLoginType"))
        assertEquals("device", body.getJSONObject("comm").getString("OpenUDID"))
        assertFalse(body.getJSONObject("comm").has("tmeLoginMethod"))
        assertEquals("", calls[4].cookie)
    }

    @Test
    fun 微信扫码生成会话并独立换票() {
        val calls = mutableListOf<QqQrHttpRequest>()
        val api = client { req ->
            calls += req
            when (calls.size) {
                1 -> response("""<img src="/connect/qrcode/wx-uuid" /><script>var u="?uuid=wx-uuid"</script>""")
                2 -> PlatformHttpResponse(200, png, emptyMap())
                3 -> response("window.wx_errcode=405;window.wx_code='wx-code';")
                4 -> credential()
                else -> error("请求次数异常")
            }
        }
        val qr = api.createQrCode(PlatformLoginMethod.WECHAT_QR_CODE, "wx-device")
        assertEquals("wx-uuid", qr.key)
        assertEquals("请使用微信扫码", qr.scanMessage)
        val result = api.checkQrCode(qr)
        assertEquals(PlatformQrLoginStatus.SUCCESS, result.status)
        assertEquals("1", result.credential.cookieValues()["loginType"])
        assertEquals("", calls[2].cookie)
        val body = JSONObject(calls[3].json!!)
        val login = body.getJSONObject("qrLogin")
        assertEquals("music.login.LoginServer", login.getString("module"))
        assertEquals("Login", login.getString("method"))
        assertEquals("wx-code", login.getJSONObject("param").getString("code"))
        assertEquals("wx48db31d50e334801", login.getJSONObject("param").getString("strAppid"))
        assertEquals(1, body.getJSONObject("comm").getInt("tmeLoginType"))
    }

    @Test
    fun 两种扫码状态和拒绝均不提前判定登录成功() {
        listOf(false, true).forEach { wechat ->
            val method = if (wechat) PlatformLoginMethod.WECHAT_QR_CODE else PlatformLoginMethod.QR_CODE
            listOf(
                (if (wechat) 408 else 66) to PlatformQrLoginStatus.WAITING_SCAN,
                (if (wechat) 404 else 67) to PlatformQrLoginStatus.WAITING_CONFIRM,
                (if (wechat) 402 else 65) to PlatformQrLoginStatus.EXPIRED,
                (if (wechat) 403 else 68) to PlatformQrLoginStatus.FAILED,
                999 to PlatformQrLoginStatus.FAILED,
            ).forEach { (code, expected) ->
                val result = client {
                    response(if (wechat) "window.wx_errcode=$code;window.wx_code='';" else "ptuiCB('$code','0','','0','状态','');")
                }.checkQrCode(challenge(method))
                assertEquals(expected, result.status)
                assertNull(result.account)
            }
        }
    }

    @Test
    fun 微信长轮询超时继续等待但普通网络错误不吞掉() {
        assertEquals(PlatformQrLoginStatus.WAITING_SCAN, client { throw SocketTimeoutException() }
            .checkQrCode(challenge(PlatformLoginMethod.WECHAT_QR_CODE)).status)
        val error = java.io.IOException("网络中断")
        assertSame(error, runCatching { client { throw error }.checkQrCode(challenge(PlatformLoginMethod.WECHAT_QR_CODE)) }.exceptionOrNull())
    }

    @Test
    fun 缺少会话或返回网页时拒绝当作二维码() {
        assertTrue(runCatching { client { PlatformHttpResponse(200, png, emptyMap()) }
            .createQrCode(PlatformLoginMethod.QR_CODE, "device") }.exceptionOrNull() is PlatformApiException)
        assertTrue(runCatching { client { response("<html>风控</html>", mapOf("Set-Cookie" to listOf("qrsig=session"))) }
            .createQrCode(PlatformLoginMethod.QR_CODE, "device") }.exceptionOrNull() is PlatformApiException)
        assertTrue(runCatching { client { response("<html>暂不可用</html>") }
            .createQrCode(PlatformLoginMethod.WECHAT_QR_CODE, "device") }.exceptionOrNull() is PlatformApiException)
    }

    @Test
    fun 账号受限或缺少音乐凭据时不返回成功() {
        listOf(
            """{"code":0,"qrLogin":{"code":20279,"data":{}}}""",
            """{"code":0,"qrLogin":{"code":0,"data":{"musicid":123456}}}""",
            """{"code":0,"qrLogin":{"data":{"musicid":123456,"musickey":"key"}}}""",
        ).forEach { json ->
            val error = runCatching { QqQrCredentialClient { response(json) }
                .authorize(PlatformLoginMethod.QR_CODE, "code", "device") }.exceptionOrNull()
            assertTrue(error is PlatformApiException)
        }
    }

    @Test
    fun 安全挑战交回现有验证窗口() {
        val error = runCatching { QqQrCredentialClient { response("""{"code":0,"qrLogin":{"code":20276,
            "data":{"securityURL":"https://y.qq.com/m/client/safety_captcha/index.html"}}}""") }
            .authorize(PlatformLoginMethod.WECHAT_QR_CODE, "code", "device") }.exceptionOrNull()
        assertTrue(error is PlatformSecurityVerificationRequired)
        assertEquals(MusicSource.QQ, (error as PlatformSecurityVerificationRequired).challenge.source)
    }

    @Test
    fun 换票携带安全验证Cookie且不混入扫码签名() {
        val result = QqQrCredentialClient { req ->
            assertEquals("verify=passed", req.cookie)
            credential()
        }.authorize(PlatformLoginMethod.WECHAT_QR_CODE, "code", "device", "verify=passed")
        assertEquals("passed", result.cookie.cookieValues()["verify"])
        assertFalse(result.cookie.cookieValues().containsKey("qrsig"))
    }

    @Test
    fun 只接受音乐官方授权跳转且支持末尾code参数() {
        assertEquals("token", qqQrAuthorizationCode("https://y.qq.com/portal/wx_redirect.html?code=token"))
        listOf("https://evil.example/portal/wx_redirect.html?code=x", "http://y.qq.com/portal/wx_redirect.html?code=x",
            "https://y.qq.com.evil.example/portal/wx_redirect.html?code=x", "https://y.qq.com:8443/portal/wx_redirect.html?code=x",
            "https://y.qq.com/other?code=x", "https://y.qq.com/portal/wx_redirect.html?state=x").forEach {
            assertTrue(runCatching { qqQrAuthorizationCode(it) }.exceptionOrNull() is PlatformApiException)
        }
    }

    @Test
    fun 回调中的昵称引号不影响票据解析且从不执行脚本() {
        val parsed = parseQqQrCallback("ptuiCB('66','0','','0','等待','O\\'Brien');alert('无关');")
        assertEquals(66, parsed.code)
        assertTrue(runCatching { parseQqQrCallback("alert('0')") }.exceptionOrNull() is PlatformApiException)
        assertEquals(108966, qqQrToken("abc"))
        assertEquals(193485963, qqQrToken("abc", 5381))
    }
}
