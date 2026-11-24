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
    private val onRejected: (Throwable) -> Unit,
    private val retryWindowMs: Long = 60_000L,
    private val forcedCooldownMs: Long = 10 * 60_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    // 同一张票刚续失败时先不重复请求，避免并发请求连续打登录接口。
    @Volatile private var failed: Pair<String, Long>? = null
    // 被拒后强制续出的新票及时间：新票马上又被同类接口拒绝，多半是接口自身限制（如权益），不再换票。
    @Volatile private var forced: Pair<String, Long>? = null

    /** 当前票是否刚由强制续票得到；此时再被拒不能判定为登录失效。 */
    fun recentlyForced(credential: String): Boolean =
        forced?.let { it.first == credential && nowMs() - it.second < forcedCooldownMs } == true

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
        if (blocked(current.credential)) {
            QqSessionLog.record("强制续票跳过：同票刚续失败，退避中")
            return@withLock null
        }
        if (recentlyForced(current.credential)) {
            QqSessionLog.record("强制续票跳过：新票刚续出又被拒，按接口限制处理")
            return@withLock null
        }
        try {
            attempt(current) { keeper.renew(current) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock null
        }
        session().takeIf { it.credential != rejected.credential && sameAccount(it, rejected) }
            ?.also { forced = it.credential to nowMs() }
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
            if (error.rejectsQqCredential()) {
                QqSessionLog.record("标记登录失效：${error.qqLogSummary()}")
                onRejected(error)
            } else QqSessionLog.record("续票或校验失败（不判失效）：${error.qqLogSummary()}")
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
