package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 所有续票串行执行：拿到锁后重新读票，别人已续好就不再请求。
 * 只有被校验的票仍是当前票时结果才算数，旧结果不能覆盖新票的状态。
 */
internal class QqSessionRenewal(
    private val session: () -> PlatformSession,
    private val keeper: QqPersistentSession,
    private val save: suspend (PlatformSession, QqValidatedSession) -> Unit,
    private val onRejected: () -> Unit,
    private val retryWindowMs: Long = 60_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    // 同一张票刚续失败时先不重复请求，避免并发请求连续打登录接口。
    @Volatile private var failed: Pair<String, Long>? = null

    /** 宿主定期校验：核实账号，票快到期时顺带续期，失败抛给调用方。 */
    suspend fun validate() {
        lock.withLock {
            val previous = session()
            if (previous.credential.isNotBlank()) attempt(previous) { keeper.validate(previous) }
        }
    }

    /** 请求前检查：只在票快到期时续期，未到期不拿锁也不联网。 */
    suspend fun prepare() {
        if (!due(session().credential)) return
        lock.withLock {
            val previous = session()
            if (due(previous.credential)) attempt(previous) { keeper.validate(previous) }
        }
    }

    /** 服务端拒绝这张票时强制续票；返回可用于重试的会话，续不上返回 null。 */
    suspend fun renewRejected(rejected: PlatformSession): PlatformSession? = lock.withLock {
        val current = session()
        if (current.credential.isBlank()) return@withLock null
        if (current.credential != rejected.credential) return@withLock current.takeIf { sameAccount(it, rejected) }
        if (blocked(current.credential)) return@withLock null
        try {
            attempt(current) { keeper.renew(current) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock null
        }
        session().takeIf { it.credential != rejected.credential && sameAccount(it, rejected) }
    }

    private suspend fun attempt(previous: PlatformSession, work: () -> QqValidatedSession) {
        try {
            val result = work()
            currentCoroutineContext().ensureActive()
            if (session().credential != previous.credential) return
            save(previous, result)
            // 校验通过但票仍未续上时，同样按失败间隔再试，不能每个请求都重试一次。
            failed = if (QqPersistentSession.shouldRefresh(result.credential, nowMs() / 1_000L)) result.credential to nowMs() else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 期间已重新登录或完成验证时，这次结果作废，不影响新票。
            if (session().credential != previous.credential) return
            failed = previous.credential to nowMs()
            if (error.rejectsQqCredential()) onRejected()
            throw error
        }
    }

    private fun due(credential: String): Boolean = credential.isNotBlank() &&
        QqPersistentSession.shouldRefresh(credential, nowMs() / 1_000L) && !blocked(credential)

    private fun blocked(credential: String): Boolean =
        failed?.let { it.first == credential && nowMs() - it.second < retryWindowMs } == true

    private fun sameAccount(left: PlatformSession, right: PlatformSession): Boolean =
        qqCredentialAccountId(left.credential) == qqCredentialAccountId(right.credential)
}
