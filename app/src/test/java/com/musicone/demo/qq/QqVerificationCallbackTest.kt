package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class QqVerificationCallbackTest {
    @Test
    fun 识别官方页面和关闭回调() {
        assertTrue(qqVerificationPage("https://y.qq.com/m/client/safety_captcha/index.html"))
        assertTrue(qqVerificationCloseCallback("qqmusic://qq.com/ui/closeWebview?p=%7B%7D#58"))
        assertFalse(qqVerificationCloseCallback("https://y.qq.com/ui/closeWebview"))
    }

    @Test
    fun 短链验证同时为c站和主页域名写入Cookie() {
        val shortUrl = "https://c.y.qq.com/r/fy6U?validatetype=10"
        assertEquals(
            listOf(shortUrl, "https://c.y.qq.com/", "https://y.qq.com/"),
            qqVerificationCookieTargets(shortUrl),
        )
        assertEquals(
            listOf("https://turing.captcha.qcloud.com/verify"),
            qqVerificationCookieTargets("https://turing.captcha.qcloud.com/verify"),
        )
    }

    @Test
    fun 播放验证地址交给官方QQ音乐打开() {
        val url = "https://y.qq.com/m/client/safety_captcha/index.html?appid=51838&_vkey_verify=1"
        val deepLink = requireNotNull(qqOfficialVerificationDeepLink(url))
        val uri = URI.create(deepLink)
        val payload = URLDecoder.decode(
            uri.rawQuery.substringAfter("p="),
            StandardCharsets.UTF_8.toString(),
        )

        assertTrue(qqVkeyVerificationPage(url))
        assertEquals("qqmusic", uri.scheme)
        assertEquals("qq.com", uri.host)
        assertEquals("/ui/openUrl", uri.path)
        assertEquals(url, JSONObject(payload).getString("url"))
        assertNull(qqOfficialVerificationDeepLink("https://example.com/?_vkey_verify=1"))
    }
}
