package com.musicone.source.qq.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPlaybackErrorsTest {
    @Test
    fun 请求拒绝保留业务码但不显示服务端IP或误判验证码与过期() {
        val response = JSONObject().put("code", 0).put("req_1",
            JSONObject().put("code", 104009).put("data",
                JSONObject().put("msg", "203.0.113.8;invalid")))
        val error = runCatching {
            requireQqMusicUSuccess(response, "uin=12001", QqRequestOrigin.PLAYBACK)
        }.exceptionOrNull()
        assertTrue(error is QqRequestRejectedException)
        assertFalse(error is QqCredentialExpiredException)
        assertFalse(error is PlatformSecurityVerificationRequired)
        error as QqRequestRejectedException
        assertEquals(104009, error.apiCode)
        assertTrue(error.stopsPlaybackFallback())
        assertTrue(error.message.orEmpty().contains("104009"))
        assertTrue(error.diagnostic.contains("提示invalid=true"))
        assertFalse(error.message.orEmpty().contains("203.0.113.8"))
        assertFalse(error.diagnostic.contains("203.0.113.8"))
    }

    @Test
    fun vkey风控读取官方validUrl并补入验证标记() {
        val response = JSONObject().put("code", 0).put(
            "req_1",
            JSONObject().put("code", 104009).put(
                "data",
                JSONObject().put(
                    "validUrl",
                    "https://c.y.qq.com/r/fy6U?validatetype=10&appid=51838",
                ),
            ),
        )

        val error = runCatching {
            requireQqMusicUSuccess(response, "uin=12002", QqRequestOrigin.PLAYBACK)
        }.exceptionOrNull() as PlatformSecurityVerificationRequired

        assertEquals(PlatformSecurityVerificationKind.WEB, error.challenge.kind)
        assertEquals(
            "https://c.y.qq.com/r/fy6U?validatetype=10&appid=51838&_vkey_verify=1",
            error.challenge.url,
        )
    }

    @Test
    fun 单项104009使用父级validUrl而不退化为音质不可用() {
        val data = JSONObject()
            .put("validUrl", "https://c.y.qq.com/r/fy6U?validatetype=10")
            .put("midurlinfo", JSONArray().put(JSONObject().put("result", 104009).put("purl", "")))

        val error = runCatching {
            data.throwIfQqPlaybackItemsFailed("uin=12003", QqRequestOrigin.QUALITY_PROBE)
        }.exceptionOrNull()

        assertTrue(error is PlatformSecurityVerificationRequired)
    }

    @Test
    fun 顶层业务错误保留错误码和请求来源() {
        val error = runCatching {
            requireQqMusicUSuccess(
                JSONObject().put("code", 9001).put("message", "请求被拒绝"),
                "uin=10001",
                QqRequestOrigin.PLAYBACK,
            )
        }.exceptionOrNull() as QqSessionRequestException

        assertEquals(9001, error.apiCode)
        assertTrue(error.diagnostic.contains("用户点播"))
        assertTrue(error.diagnostic.contains("顶层"))
    }

    @Test
    fun 子请求凭证过期不会退化为空播放地址() {
        val response = JSONObject().put("code", 0).put(
            "req_1",
            JSONObject().put("code", 104401).put("data", JSONObject()),
        )

        val error = runCatching {
            requireQqMusicUSuccess(response, "uin=10002", QqRequestOrigin.QUALITY_PROBE)
        }.exceptionOrNull()

        assertTrue(error is QqCredentialExpiredException)
        assertEquals(104401, (error as QqCredentialExpiredException).apiCode)
    }

    @Test
    fun 风控反馈链接只允许受信任的Https域名() {
        val trusted = JSONObject().put("code", 0).put(
            "req_1",
            JSONObject().put("code", 2001).put(
                "data",
                JSONObject().put("feedbackURL", "https://support.qq.com/verify?id=secret"),
            ),
        )
        val trustedError = runCatching {
            requireQqMusicUSuccess(trusted, "uin=10003", QqRequestOrigin.PLAYBACK)
        }.exceptionOrNull() as PlatformSecurityVerificationRequired
        assertEquals(PlatformSecurityVerificationKind.WEB, trustedError.challenge.kind)
        assertEquals("https://support.qq.com/verify?id=secret", trustedError.challenge.url)

        val untrusted = JSONObject().put("code", 0).put(
            "req_1",
            JSONObject().put("code", 2001).put(
                "data",
                JSONObject().put("feedbackURL", "https://example.com/verify"),
            ),
        )
        val untrustedError = runCatching {
            requireQqMusicUSuccess(untrusted, "uin=10004", QqRequestOrigin.PLAYBACK)
        }.exceptionOrNull()
        assertTrue(untrustedError is QqSessionRequestException)
        assertFalse(untrustedError is PlatformSecurityVerificationRequired)
    }

    @Test
    fun 只有已知业务码与对应可信地址同时出现才要求验证() {
        val invalidResponses = listOf(
            JSONObject().put("code", 2001),
            JSONObject().put("code", 2001).put("feedbackURL", JSONObject.NULL),
            JSONObject().put("code", 2001).put("feedbackURL", 123),
            JSONObject().put("code", 2001).put("feedbackURL", "null"),
            JSONObject().put("code", 2001).put("feedbackURL", "http://support.qq.com/verify"),
            JSONObject().put("code", 2001).put("feedbackURL", "https://qq.com.example.com/verify"),
            JSONObject().put("code", 9001).put("feedbackURL", "https://support.qq.com/verify"),
            JSONObject().put("code", 104401).put("validUrl", "https://c.y.qq.com/verify"),
            JSONObject().put("code", 2001).put("validUrl", "https://c.y.qq.com/verify"),
            JSONObject().put("code", 104009).put("feedbackURL", "https://support.qq.com/verify"),
        )
        invalidResponses.forEach { response ->
            val error = runCatching {
                requireQqMusicUSuccess(response, "uin=10007", QqRequestOrigin.PLAYBACK)
            }.exceptionOrNull()
            assertTrue(error is QqSessionRequestException)
            assertFalse(error is PlatformSecurityVerificationRequired)
        }
    }

    @Test
    fun 单项普通错误带链接不应覆盖同批可用音源() {
        JSONObject().put("midurlinfo", JSONArray()
            .put(JSONObject().put("result", 740).put("validUrl", "https://c.y.qq.com/verify"))
            .put(JSONObject().put("result", 0).put("purl", "M500song.mp3")))
            .throwIfQqPlaybackItemsFailed("uin=10008", QqRequestOrigin.PLAYBACK)
    }

    @Test
    fun 辅助请求解析到真实验证也不能直接暂停取票或弹窗() {
        QqSessionRequestCoordinator.cancel()
        listOf(QqRequestOrigin.OTHER, QqRequestOrigin.ACCESS_PROBE, QqRequestOrigin.VIP_PROBE,
            QqRequestOrigin.PRELOAD).forEach { origin ->
            val error = runCatching {
                requireQqMusicUSuccess(JSONObject().put("code", 2001)
                    .put("feedbackURL", "https://support.qq.com/verify"), "uin=10009", origin)
            }.exceptionOrNull()
            assertTrue(error is PlatformSecurityVerificationRequired)
            assertEquals(null, QqSessionRequestCoordinator.verification.value)
            QqSessionRequestCoordinator.beforeTicketRequest("uin=10009")
        }
    }

    @Test
    fun 单项错误仅在整批没有地址时标记当前音质不可用() {
        val failed = JSONObject().put(
            "midurlinfo",
            JSONArray().put(JSONObject().put("result", 740).put("purl", "")),
        )
        val error = runCatching {
            failed.throwIfQqPlaybackItemsFailed("uin=10005", QqRequestOrigin.PLAYBACK)
        }.exceptionOrNull()
        assertTrue(error is QqPlaybackItemUnavailableException)
        assertEquals(740, (error as QqPlaybackItemUnavailableException).apiCode)

        val mixed = JSONObject().put(
            "midurlinfo",
            JSONArray()
                .put(JSONObject().put("result", 740).put("purl", ""))
                .put(JSONObject().put("result", 0).put("purl", "M500song.mp3")),
        )
        mixed.throwIfQqPlaybackItemsFailed("uin=10006", QqRequestOrigin.QUALITY_PROBE)
    }

    @Test
    fun 会话错误会终止音质回退而单项错误允许降档() {
        assertTrue(QqCredentialExpiredException(104401, "test").stopsPlaybackFallback())
        assertTrue(PlatformSecurityVerificationRequired(
            PlatformSecurityChallenge(MusicSource.QQ, PlatformSecurityVerificationKind.MANUAL),
        ).stopsPlaybackFallback())
        assertTrue(!QqPlaybackItemUnavailableException("不可用", 740, "test").stopsPlaybackFallback())
    }
}
