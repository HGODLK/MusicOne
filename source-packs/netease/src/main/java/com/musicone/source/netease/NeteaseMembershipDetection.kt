package com.musicone.source.netease

import com.musicone.source.sdk.SourceMembershipDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** 平台资料中的音乐包与黑胶类型才确认音乐会员，其他结果保持 unknown。 */
internal fun JSONObject.neteaseConfirmedMusicMembership(): Boolean? {
    val profile = optJSONObject("profile")
    val account = optJSONObject("account")
    val types = listOf(profile?.opt("vipType"), profile?.opt("viptype"), account?.opt("vipType"))
    return true.takeIf { types.any { it?.toString()?.toIntOrNull() in setOf(10, 11) } }
}

internal class NeteaseMembershipDetection(private val state: () -> NeteasePackageState?) : SourceMembershipDetector {
    private data class Session(val credential: String, val accountId: String, val revision: Long)
    private data class Result(val session: Session, val member: Boolean?, val refreshAtMs: Long)
    private val mutex = Mutex()
    @Volatile private var result: Result? = null

    private fun session(owner: NeteasePackageState): Session? {
        if (owner.expired) return null
        val credential = owner.credential().takeIf(String::isNotBlank) ?: return null
        val accountId = owner.account?.userId?.takeIf(String::isNotBlank) ?: return null
        return Session(credential, accountId, owner.revision.get())
    }

    override val currentMembership: Boolean?
        get() {
            val current = state()?.let(::session) ?: return null
            return result?.takeIf { it.session == current && System.currentTimeMillis() < it.refreshAtMs }?.member
        }

    override suspend fun detectMembership(): Boolean? = mutex.withLock {
        val owner = state() ?: return null
        val captured = session(owner) ?: return null
        if (result?.let { it.session == captured && System.currentTimeMillis() < it.refreshAtMs } == true) return currentMembership
        val account = try {
            runInterruptible(Dispatchers.IO) { owner.api.account(captured.credential) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        synchronized(owner) {
            owner.check(captured.revision)
            if (session(owner) != captured || account != null && account.userId != captured.accountId) return null
            if (account != null) owner.saveSession(captured.credential, account, captured.revision, refreshing = true)
            // 五分钟核对成功资料，失败只暂存 unknown，避免每首歌重复发账号查询。
            val refreshAt = System.currentTimeMillis() + if (account != null) 5 * 60_000L else 30_000L
            result = Result(captured, account?.confirmedMusicMembership, refreshAt)
        }
        currentMembership
    }
}
