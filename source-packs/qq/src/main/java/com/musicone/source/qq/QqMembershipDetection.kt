package com.musicone.source.qq

import com.musicone.source.qq.internal.QqMembership
import com.musicone.source.qq.internal.QqMembershipClient
import com.musicone.source.sdk.SourceMembershipDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 可选声明只复用真实音乐会员查询，不改变原歌曲权益缓存及播放判断。 */
internal class QqMembershipDetection(
    private val session: () -> Pair<String, Long>?,
    private val query: (String) -> QqMembership? = QqMembershipClient()::query,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SourceMembershipDetector {
    private data class Result(val session: Pair<String, Long>, val membership: QqMembership?, val refreshAtMs: Long)
    private val mutex = Mutex()
    @Volatile private var result: Result? = null

    override val currentMembership: Boolean?
        get() {
            val current = session() ?: return null
            val member = result?.takeIf { it.session == current }?.membership
            return true.takeIf { member?.effectiveAt(nowMs())?.vip == true }
        }

    override suspend fun detectMembership(): Boolean? = mutex.withLock {
        val captured = session() ?: return null
        if (result?.let { it.session == captured && nowMs() < it.refreshAtMs } == true) return currentMembership
        val member = try {
            runInterruptible(Dispatchers.IO) { query(captured.first) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (session() != captured) return null
        // 原查询给出刷新时机；失败短暂复用 unknown，合并同屏歌曲的重复查询。
        result = Result(captured, member, member?.validUntilMs ?: (nowMs() + 30_000L))
        currentMembership
    }
}
