package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseSmsLoginClientTest {
    private fun response(body: JSONObject, status: Int = 200, cookies: List<String> = emptyList()) =
        PlatformHttpResponse(status, body.toString().toByteArray(), mapOf("Set-Cookie" to cookies))

    @Test fun smsAndLoginShareOnlyTheServerSessionAndUseTheWebLoginPath() {
        val requests = mutableListOf<NeteaseSmsRequest>()
        val client = NeteaseSmsLoginClient { request ->
            requests += request
            if (requests.size == 1) response(JSONObject().put("code", 200), cookies = listOf("session=one; Path=/"))
            else response(JSONObject().put("code", 200), cookies = listOf("MUSIC_U=authenticated; HttpOnly"))
        }
        val sent = client.sendCaptcha("13800000000", "86", "")
        val cookie = client.login("13800000000", "86", "1234", sent.cookie)
        assertEquals("", requests[0].cookie)
        assertEquals("session=one", requests[1].cookie)
        assertEquals("https://interface.music.163.com/eapi/w/login/cellphone", requests[1].url)
        val payload = requests[1].payload
        assertEquals(1, payload.get("type"))
        assertEquals(true, payload.get("remember"))
        assertEquals(false, payload.get("e_r"))
        assertFalse(payload.has("verifyId"))
        assertFalse(payload.has("rnBundleName"))
        assertFalse(payload.has("deviceId"))
        assertEquals("ios", payload.getJSONObject("header").getString("os"))
        assertTrue(payload.getJSONObject("header").getString("requestId").isNotBlank())
        assertFalse(requests[1].headers.containsKey("X-AEAPI"))
        assertTrue(neteaseHasLoginCookie(cookie))
    }

    @Test fun http400RetainsTheSecurityChallengeInsteadOfLosingItsBusinessResponse() {
        val client = NeteaseSmsLoginClient { response(JSONObject().put("code", 8821)
            .put("redirectUrl", "https://qa-yyy.igame.163.com/anquanhuanjingfengxian"), 400) }
        try { client.login("13800000000", "86", "1234", ""); fail("应返回安全挑战") }
        catch (error: PlatformSecurityVerificationRequired) { assertEquals(MusicSource.NETEASE, error.challenge.source) }
    }

    @Test fun ordinaryCaptchaFailureIsNotOpenedAsASecurityChallengeAndDeviceCookiesAreNotLogin() {
        val client = NeteaseSmsLoginClient { response(JSONObject().put("code", 502).put("message", "验证码错误")) }
        try { client.login("13800000000", "86", "1234", ""); fail("应返回验证码错误") }
        catch (error: NeteaseApiException) { assertEquals("验证码错误", error.message) }
        val missing = NeteaseSmsLoginClient { response(JSONObject().put("code", 200), cookies = listOf("device=one")) }
        try { missing.login("13800000000", "86", "1234", ""); fail("设备 Cookie 不能作为成功凭据") }
        catch (error: NeteaseApiException) { assertTrue(error.message.orEmpty().contains("有效登录凭据")) }
    }

    @Test fun responseCookieDeletionAndReplacementArePreserved() {
        assertEquals(mapOf("session" to "new"), neteaseMergeResponseCookies("session=old; stale=value",
            listOf("stale=; Max-Age=0; Path=/", "session=new; Path=/")).cookieValues())
    }
}
