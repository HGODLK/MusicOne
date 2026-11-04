package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal data class QqPlaybackVerificationState(
    val challenge: PlatformSecurityChallenge,
    val message: String? = null,
    val instanceId: String = java.util.UUID.randomUUID().toString(),
)

/** 取票只等待当前凭据正在进行的验证，不保存账号级暂停标记。 */
internal object QqSessionRequestCoordinator {
    private class Pending(
        val cookie: String,
        val challenge: PlatformSecurityChallenge,
        val completion: CompletableDeferred<String> = CompletableDeferred(),
        var waiters: Int = 0,
    )

    private val pacers = ConcurrentHashMap<String, QqTicketRequestPacer>()
    private val guids = ConcurrentHashMap<String, String>()
    private val lock = Any()
    private var sessionCredential: String? = null
    private var pending: Pending? = null
    private val mutableVerification = MutableStateFlow<QqPlaybackVerificationState?>(null)
    val verification = mutableVerification.asStateFlow()

    fun rememberGuid(cookie: String, guid: String?) {
        if (!guid.isNullOrBlank()) guids[sessionKey(cookie)] = guid
    }

    fun guid(cookie: String): String = guids[sessionKey(cookie)]
        ?: qqStableGuid("qq-${sessionKey(cookie)}")

    fun beforeTicketRequest(cookie: String) {
        val challenge = synchronized(lock) { pending?.takeIf { it.cookie == cookie }?.challenge }
        if (challenge != null) throw PlatformSecurityVerificationRequired(challenge)
    }

    suspend fun beforeCacheTicketRequest(cookie: String) {
        beforeTicketRequest(cookie)
        val key = sessionKey(cookie)
        val pacer = pacers[key] ?: QqTicketRequestPacer().let { candidate ->
            pacers.putIfAbsent(key, candidate) ?: candidate
        }
        pacer.awaitTurn()
        beforeTicketRequest(cookie)
    }

    suspend fun awaitVerification(cookie: String, challenge: PlatformSecurityChallenge): String {
        currentCoroutineContext().ensureActive()
        val active = synchronized(lock) {
            if (sessionCredential != null && sessionCredential != cookie) {
                throw CancellationException("QQ登录凭据已变化，旧验证已取消")
            }
            val current = pending
            if (current != null && current.cookie != cookie) {
                throw QqSessionRequestException("另一会话正在验证，请重试", diagnostic = "验证凭据不同，不合并请求")
            }
            (current ?: Pending(cookie, challenge).also {
                pending = it
                mutableVerification.value = QqPlaybackVerificationState(challenge)
            }).also { it.waiters++ }
        }
        return try {
            active.completion.await()
        } finally {
            synchronized(lock) {
                active.waiters--
                // 切歌、离页或任务取消后，最后一个等待者负责释放验证窗口。
                if (pending === active && active.waiters == 0) clearPending()
            }
        }
    }

    fun complete(result: PlatformSecurityVerificationResult, challenge: PlatformSecurityChallenge): String? {
        val (active, credential) = synchronized(lock) {
            val current = pending?.takeIf { it.challenge === challenge } ?: return null
            val merged = mergePlatformCredentials(current.cookie, result.cookie)
            if (sessionCredential != null) sessionCredential = merged
            clearPending()
            current to merged
        }
        active.completion.complete(credential)
        return credential
    }

    fun cancel(challenge: PlatformSecurityChallenge? = null) {
        val active = synchronized(lock) {
            if (challenge != null && pending?.challenge !== challenge) return
            clearPending()
        }
        active?.completion?.cancel(CancellationException("QQ音乐安全验证已取消"))
    }

    fun sessionChanged(cookie: String) {
        val active = synchronized(lock) {
            sessionCredential = cookie
            clearPending()
        }
        active?.completion?.cancel(CancellationException("QQ登录会话已变化，旧验证已取消"))
    }

    private fun clearPending(): Pending? {
        val active = pending
        pending = null
        mutableVerification.value = null
        return active
    }

    private fun sessionKey(cookie: String): String = qqCredentialAccountId(cookie).ifBlank { "guest" }
}

internal suspend fun <T> retryQqRequestAfterVerification(
    cookie: String,
    interactive: Boolean = true,
    request: suspend (String) -> T,
): T {
    return try {
        request(cookie)
    } catch (required: PlatformSecurityVerificationRequired) {
        // 后台请求只报告本次验证要求；下次点播仍需向服务器取得新结果。
        if (!interactive) throw required
        val refreshed = QqSessionRequestCoordinator.awaitVerification(cookie, required.challenge)
        try {
            request(refreshed)
        } catch (again: PlatformSecurityVerificationRequired) {
            // 分类器不再发布暂停，单次重试失败也不会创建第二个验证窗口。
            throw QqSessionRequestException(
                "QQ音乐本次请求仍需验证，请稍后重试",
                diagnostic = "安全验证后单次重试仍被拦截",
            )
        }
    }
}
