package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal data class QqPlaybackVerificationState(
    val challenge: PlatformSecurityChallenge,
    val message: String? = null,
)

/** QQ 换票共享会话暂停状态；固定间隔仅用于主动批量缓存。 */
internal object QqSessionRequestCoordinator {
    private data class Pending(
        val challenge: PlatformSecurityChallenge,
        val completion: CompletableDeferred<String>,
    )

    private val pacers = ConcurrentHashMap<String, QqTicketRequestPacer>()
    private val guids = ConcurrentHashMap<String, String>()
    private val paused = ConcurrentHashMap<String, PlatformSecurityChallenge>()
    private val lock = Any()
    private var pending: Pending? = null
    private val mutableVerification = MutableStateFlow<QqPlaybackVerificationState?>(null)
    val verification = mutableVerification.asStateFlow()

    fun rememberGuid(cookie: String, guid: String?) {
        if (!guid.isNullOrBlank()) guids[sessionKey(cookie)] = guid
    }

    fun guid(cookie: String): String = guids[sessionKey(cookie)]
        ?: qqStableGuid("qq-${sessionKey(cookie)}")

    fun beforeTicketRequest(cookie: String, showVerification: Boolean = true) {
        val key = sessionKey(cookie)
        paused[key]?.let { challenge ->
            if (showVerification) publish(challenge)
            throw PlatformSecurityVerificationRequired(challenge)
        }
    }

    suspend fun beforeCacheTicketRequest(cookie: String, showVerification: Boolean = true) {
        beforeTicketRequest(cookie, showVerification)
        val key = sessionKey(cookie)
        val pacer = pacers[key] ?: QqTicketRequestPacer().let { candidate ->
            pacers.putIfAbsent(key, candidate) ?: candidate
        }
        pacer.awaitTurn()
        beforeTicketRequest(cookie, showVerification)
    }

    fun pause(cookie: String, challenge: PlatformSecurityChallenge, showVerification: Boolean = true) {
        paused[sessionKey(cookie)] = challenge
        if (showVerification) publish(challenge)
    }

    suspend fun awaitVerification(cookie: String, challenge: PlatformSecurityChallenge): String {
        pause(cookie, challenge)
        val deferred = synchronized(lock) {
            val current = pending
            if (current != null && current.challenge.credentialSeed == challenge.credentialSeed) current.completion
            else CompletableDeferred<String>().also { pending = Pending(challenge, it) }
        }
        return deferred.await()
    }

    fun complete(result: PlatformSecurityVerificationResult): String? {
        val active = synchronized(lock) { pending.also { pending = null } } ?: return null
        val credential = mergePlatformCredentials(active.challenge.credentialSeed, result.cookie)
        paused.remove(sessionKey(active.challenge.credentialSeed))
        mutableVerification.value = null
        active.completion.complete(credential)
        return credential
    }

    fun cancel() {
        val active = synchronized(lock) { pending.also { pending = null } }
        mutableVerification.value = null
        active?.completion?.completeExceptionally(
            PlatformSecurityVerificationRequired(active.challenge),
        )
    }

    fun finishFailedRetry() {
        synchronized(lock) { pending = null }
        mutableVerification.value = null
    }

    private fun publish(challenge: PlatformSecurityChallenge) {
        synchronized(lock) {
            if (pending == null) pending = Pending(challenge, CompletableDeferred())
        }
        mutableVerification.value = QqPlaybackVerificationState(challenge)
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
        // 预取只记录会话暂停，不弹窗、不等待用户，实际点播时再沿用验证流程。
        if (!interactive) throw required
        val refreshed = QqSessionRequestCoordinator.awaitVerification(cookie, required.challenge)
        try {
            request(refreshed)
        } catch (again: PlatformSecurityVerificationRequired) {
            QqSessionRequestCoordinator.finishFailedRetry()
            throw QqSessionRequestException(
                "QQ 音乐安全验证尚未生效，请稍后再试",
                diagnostic = "安全验证后单次重试仍被拦截",
            )
        }
    }
}
