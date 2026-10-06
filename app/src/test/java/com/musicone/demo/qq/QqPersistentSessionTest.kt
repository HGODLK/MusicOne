package com.musicone.demo

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
    fun 到期续期失败且旧票据失效时不重复请求() {
        val old = "musicid=123456; musickey=old; musickeyCreateTime=1000; keyExpiresIn=3600"
        var refreshCount = 0
        val keeper = QqPersistentSession(
            refresh = { _, _ -> refreshCount++; throw PlatformApiException("续期失败") },
            account = { throw PlatformApiException("旧票据失效", 301) },
        )
        val error = runCatching { keeper.validate(session(old), nowSeconds = 4_100) }.exceptionOrNull()
        assertEquals(301, (error as PlatformApiException).apiCode)
        assertEquals(1, refreshCount)
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
    }

    private fun session(credential: String) = PlatformSession(MusicSource.QQ, credential, "device", account)
}
