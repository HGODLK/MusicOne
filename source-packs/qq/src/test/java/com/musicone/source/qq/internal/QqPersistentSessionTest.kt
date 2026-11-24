package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPersistentSessionTest {
    private val account = MusicAccount(MusicSource.QQ, "123456", "测试账号", null)

    @Test
    fun 到期前续期并使用新票据验证账号() {
        val old = "musicid=123456; musickey=old; musickeyCreateTime=1000; keyExpiresIn=3600"
        val renewed = "musicid=123456; musickey=new; musickeyCreateTime=4600; keyExpiresIn=3600"
        val checked = mutableListOf<String>()
        val keeper = QqPersistentSession(
            refresh = { deviceId, credential ->
                assertEquals("device", deviceId)
                assertEquals(old, credential)
                renewed
            },
            account = { credential -> checked += credential; account },
        )
        val result = keeper.validate(session(old), nowSeconds = 4_100)
        assertEquals(renewed, result.credential)
        assertEquals(listOf(renewed), checked)
        assertFalse(QqPersistentSession.shouldRefresh(old, nowSeconds = 3_999))
        assertTrue(QqPersistentSession.shouldRefresh(old, nowSeconds = 4_000))
    }

    @Test
    fun 旧票据被拒绝时只续期一次再验证() {
        val old = "musicid=123456; musickey=old"
        val renewed = "musicid=123456; musickey=new"
        var refreshCount = 0
        val keeper = QqPersistentSession(
            refresh = { _, _ -> refreshCount++; renewed },
            account = { credential ->
                if (credential == old) throw PlatformApiException("已过期", 301)
                account
            },
        )
        assertEquals(renewed, keeper.validate(session(old)).credential)
        assertEquals(1, refreshCount)
    }

    @Test
    fun 提前续期暂时失败仍可使用有效旧票据() {
        val old = "musicid=123456; musickey=old; musickeyCreateTime=1000; keyExpiresIn=3600"
        val keeper = QqPersistentSession(
            refresh = { _, _ -> throw PlatformApiException("网络暂不可用") },
            account = { credential -> assertEquals(old, credential); account },
        )
        assertEquals(old, keeper.validate(session(old), nowSeconds = 4_100).credential)
    }

    @Test
    fun 到期续期临时失败且旧票据被拒时按临时故障上报且不重复请求() {
        val old = "musicid=123456; musickey=old; musickeyCreateTime=1000; keyExpiresIn=3600"
        var refreshCount = 0
        val keeper = QqPersistentSession(
            refresh = { _, _ -> refreshCount++; throw PlatformApiException("请求过于频繁", 104604) },
            account = { throw PlatformApiException("旧票据失效", 301) },
        )
        val error = runCatching { keeper.validate(session(old), nowSeconds = 4_100) }.exceptionOrNull()
        assertEquals(104604, (error as PlatformApiException).apiCode)
        assertFalse(error.rejectsQqCredential())
        assertEquals(1, refreshCount)
    }

    @Test
    fun 到期续期被明确拒绝且旧票据失效时判定失效() {
        val old = "musicid=123456; musickey=old; musickeyCreateTime=1000; keyExpiresIn=3600"
        val keeper = QqPersistentSession(
            refresh = { _, _ -> throw PlatformApiException("登录信息已过期", 1000) },
            account = { throw PlatformApiException("旧票据失效", 301) },
        )
        val error = runCatching { keeper.validate(session(old), nowSeconds = 4_100) }.exceptionOrNull()
        assertEquals(301, (error as PlatformApiException).apiCode)
    }

    @Test
    fun 校验被拒后续期遇到网络故障不判定失效() {
        val keeper = QqPersistentSession(
            refresh = { _, _ -> throw java.io.IOException("断网") },
            account = { throw PlatformApiException("旧票据失效", 301) },
        )
        val error = runCatching { keeper.validate(session("musicid=123456; musickey=old")) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun 刷新请求携带可续期字段并使用登录模式二() {
        val param = qqCredentialRefreshParam(
            "musicid=123456; musickey=old; refresh_key=refresh-key; refresh_token=refresh-token; " +
                "access_token=access-token; expired_at=12345",
        )
        assertEquals("old", param.getString("musickey"))
        assertEquals("refresh-key", param.getString("refresh_key"))
        assertEquals("refresh-token", param.getString("refresh_token"))
        assertEquals("access-token", param.getString("access_token"))
        assertEquals(12345L, param.getLong("expired_in"))
        assertEquals(2, param.getInt("loginMode"))
        assertFalse(param.has("unionid"))
        assertFalse(param.has("str_musicid"))
    }

    @Test
    fun 微信登录续期只带微信字段() {
        val param = qqCredentialRefreshParam(
            "musicid=123456; musickey=W_X_old; str_musicid=123456; unionid=union; refresh_key=rk; refresh_token=rt; " +
                "access_token=at; expired_at=1",
        )
        assertEquals("union", param.getString("unionid"))
        assertEquals("123456", param.getString("str_musicid"))
        assertEquals("rk", param.getString("refresh_key"))
        assertFalse(param.has("access_token"))
        assertFalse(param.has("expired_in"))
        assertFalse(param.has("musicid"))
    }

    private fun session(credential: String) = PlatformSession(MusicSource.QQ, credential, "device", account)
}
