package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QqSessionRequestCoordinatorTest {
    private val cookie = "uin=12001; qm_keyst=old"
    private fun challenge(credential: String = cookie) = PlatformSecurityChallenge(
        MusicSource.QQ, PlatformSecurityVerificationKind.WEB,
        url = "https://support.qq.com/verify", credentialSeed = credential,
    )

    @Before fun prepare() { QqSessionRequestCoordinator.sessionChanged(cookie) }
    @After fun clean() { QqSessionRequestCoordinator.cancel() }

    @Test fun 关闭验证后再次请求可以访问服务器() = runBlocking {
        val required = challenge()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, required)
        }
        assertTrue(runCatching { QqSessionRequestCoordinator.beforeTicketRequest(cookie) }
            .exceptionOrNull() is PlatformSecurityVerificationRequired)
        QqSessionRequestCoordinator.cancel(required)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        QqSessionRequestCoordinator.beforeTicketRequest(cookie)
        var requests = 0
        assertEquals("成功", retryQqRequestAfterVerification(cookie) { requests++; "成功" })
        assertEquals(1, requests)
        assertNull(QqSessionRequestCoordinator.verification.value)
    }

    @Test fun 验证后只重试一次且失败不遗留暂停或新弹窗() = runBlocking {
        val required = challenge()
        var requests = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                retryQqRequestAfterVerification(cookie) {
                    requests++
                    throw PlatformSecurityVerificationRequired(required)
                }
            }
        }
        QqSessionRequestCoordinator.complete(PlatformSecurityVerificationResult(), required)
        assertTrue(pending.await().exceptionOrNull() is QqSessionRequestException)
        assertEquals(2, requests)
        assertNull(QqSessionRequestCoordinator.verification.value)
        QqSessionRequestCoordinator.beforeTicketRequest(cookie)
        assertEquals("新请求", retryQqRequestAfterVerification(cookie) { "新请求" })
    }

    @Test fun 最后一个等待任务取消就释放验证() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, challenge())
        }
        pending.cancelAndJoin()
        assertNull(QqSessionRequestCoordinator.verification.value)
        QqSessionRequestCoordinator.beforeTicketRequest(cookie)
    }

    @Test fun 同一凭据并发等待时取消一个任务不影响其余请求() = runBlocking {
        val required = challenge()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, required)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, challenge())
        }
        first.cancelAndJoin()
        assertNotNull(QqSessionRequestCoordinator.verification.value)
        val refreshed = QqSessionRequestCoordinator.complete(
            PlatformSecurityVerificationResult(cookie = "qm_keyst=new"), required)
        assertEquals(refreshed, second.await())
        assertTrue(refreshed.orEmpty().contains("qm_keyst=new"))
        assertNull(QqSessionRequestCoordinator.verification.value)
        QqSessionRequestCoordinator.beforeTicketRequest(checkNotNull(refreshed))
    }

    @Test fun 同账号新凭据不继承旧验证且拒绝迟到的旧响应() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, challenge())
        }
        val renewed = "uin=12001; qm_keyst=new"
        QqSessionRequestCoordinator.beforeTicketRequest(renewed)
        QqSessionRequestCoordinator.sessionChanged(renewed)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { QqSessionRequestCoordinator.awaitVerification(cookie, challenge()) }
            .exceptionOrNull() is CancellationException)
        assertNull(QqSessionRequestCoordinator.verification.value)
        QqSessionRequestCoordinator.beforeTicketRequest(renewed)
    }

    @Test fun 退出再登录同一账号同一凭据也会释放旧等待() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, challenge())
        }
        QqSessionRequestCoordinator.sessionChanged("")
        QqSessionRequestCoordinator.sessionChanged(cookie)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertNull(QqSessionRequestCoordinator.verification.value)
        QqSessionRequestCoordinator.beforeTicketRequest(cookie)
    }

    @Test fun 旧窗口回调不能关闭或完成新验证() = runBlocking {
        val old = challenge()
        val current = challenge()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, current)
        }
        QqSessionRequestCoordinator.cancel(old)
        assertNull(QqSessionRequestCoordinator.complete(PlatformSecurityVerificationResult(), old))
        assertNotNull(QqSessionRequestCoordinator.verification.value)
        QqSessionRequestCoordinator.complete(PlatformSecurityVerificationResult(), current)
        assertNotNull(pending.await())
    }

    @Test fun 相同内容的新验证也有独立窗口身份() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, challenge())
        }
        val previous = checkNotNull(QqSessionRequestCoordinator.verification.value)
        first.cancelAndJoin()
        val required = challenge()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            QqSessionRequestCoordinator.awaitVerification(cookie, required)
        }
        val current = checkNotNull(QqSessionRequestCoordinator.verification.value)
        assertEquals(previous.challenge, current.challenge)
        assertNotEquals(previous.instanceId, current.instanceId)
        QqSessionRequestCoordinator.complete(PlatformSecurityVerificationResult(), required)
        assertNotNull(second.await())
    }
}
