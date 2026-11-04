package com.musicone.source.qq.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QqSessionRenewalTest {
    private val account = MusicAccount(MusicSource.QQ, "123456", "测试账号", null)
    private val dying = "musicid=123456; musickey=old; musickeyCreateTime=1000; keyExpiresIn=3600"
    private val fresh = "musicid=123456; musickey=new; musickeyCreateTime=9000; keyExpiresIn=3600"
    private val stable = "musicid=123456; musickey=stable"
    private val rejected = PlatformApiException("票据失效", 1000)

    private inner class Fixture(
        credential: String,
        refresh: (String) -> String = { fresh },
        check: (String) -> MusicAccount = { account },
        var now: Long = 9_000_000L,
    ) {
        @Volatile var current = credential
        var expired = false
        val refreshes = AtomicInteger()
        val renewal = QqSessionRenewal(
            session = { PlatformSession(MusicSource.QQ, current, "device", account) },
            keeper = QqPersistentSession(
                refresh = { _, value -> refreshes.incrementAndGet(); refresh(value) },
                account = check,
            ),
            save = { _, result -> current = result.credential; expired = false },
            onRejected = { expired = true },
            nowMs = { now },
        )
    }

    @Test
    fun 并发请求只续票一次() = runBlocking {
        val fixture = Fixture(dying, refresh = { Thread.sleep(50); fresh }, now = 4_000_000L)
        (1..20).map { async(Dispatchers.IO) { fixture.renewal.prepare() } }.awaitAll()
        assertEquals(1, fixture.refreshes.get())
        assertEquals(fresh, fixture.current)
        assertFalse(fixture.expired)
    }

    @Test
    fun 校验旧票失败不会把已更新的新票标成失效() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture(stable, check = {
            // 校验途中重新登录换了票，旧票随后被服务端拒绝。
            fixture.current = fresh
            throw rejected
        })
        fixture.renewal.validate()
        assertEquals(fresh, fixture.current)
        assertFalse(fixture.expired)
    }

    @Test
    fun 当前票被明确拒绝才标记失效并保留凭证() = runBlocking {
        val fixture = Fixture(stable, refresh = { throw rejected }, check = { throw rejected })
        val error = runCatching { fixture.renewal.validate() }.exceptionOrNull()
        assertTrue(error === rejected)
        assertTrue(fixture.expired)
        assertEquals(stable, fixture.current)
    }

    @Test
    fun 网络故障不标记失效() = runBlocking {
        val fixture = Fixture(stable, check = { throw java.io.IOException("断网") })
        assertTrue(runCatching { fixture.renewal.validate() }.exceptionOrNull() is java.io.IOException)
        assertFalse(fixture.expired)
    }

    @Test
    fun 票被提前作废时续票并返回新会话() = runBlocking {
        val fixture = Fixture(stable)
        val rejectedSession = PlatformSession(MusicSource.QQ, stable, "device", account)
        assertEquals(fresh, fixture.renewal.renewRejected(rejectedSession)?.credential)
        assertEquals(fresh, fixture.current)
        assertEquals(1, fixture.refreshes.get())
    }

    @Test
    fun 其他请求已续好时直接复用新票() = runBlocking {
        val fixture = Fixture(fresh)
        val rejectedSession = PlatformSession(MusicSource.QQ, stable, "device", account)
        assertEquals(fresh, fixture.renewal.renewRejected(rejectedSession)?.credential)
        assertEquals(0, fixture.refreshes.get())
    }

    @Test
    fun 已换成其他账号时不重试() = runBlocking {
        val fixture = Fixture("musicid=654321; musickey=other")
        val rejectedSession = PlatformSession(MusicSource.QQ, stable, "device", account)
        assertNull(fixture.renewal.renewRejected(rejectedSession))
        assertEquals(0, fixture.refreshes.get())
    }

    @Test
    fun 续票失败后短时间内不再请求且标记失效() = runBlocking {
        val fixture = Fixture(stable, refresh = { throw rejected })
        val rejectedSession = PlatformSession(MusicSource.QQ, stable, "device", account)
        assertNull(fixture.renewal.renewRejected(rejectedSession))
        assertTrue(fixture.expired)
        assertNull(fixture.renewal.renewRejected(rejectedSession))
        assertEquals(1, fixture.refreshes.get())
        fixture.now += 60_000L
        assertNull(fixture.renewal.renewRejected(rejectedSession))
        assertEquals(2, fixture.refreshes.get())
        assertEquals(stable, fixture.current)
    }

    @Test
    fun 快到期但续票失败时不会每个请求都重试() = runBlocking {
        val fixture = Fixture(dying, refresh = { throw java.io.IOException("断网") }, now = 4_000_000L)
        fixture.renewal.prepare()
        fixture.renewal.prepare()
        assertEquals(1, fixture.refreshes.get())
        assertEquals(dying, fixture.current)
    }

    @Test
    fun 未到期的票不触发续期() = runBlocking {
        val fixture = Fixture(fresh, now = 1_000_000L)
        fixture.renewal.prepare()
        assertEquals(0, fixture.refreshes.get())
    }

    @Test
    fun 只有登录票据错误算作被拒() {
        for (code in listOf(301, 401, 1000, 104400, 104401)) assertTrue(PlatformApiException("拒绝", code).rejectsQqCredential())
        assertTrue(QqCredentialExpiredException(104401, "测试").rejectsQqCredential())
        assertFalse(PlatformApiException("限流", 429).rejectsQqCredential())
        assertFalse(java.io.IOException("断网").rejectsQqCredential())
    }
}
