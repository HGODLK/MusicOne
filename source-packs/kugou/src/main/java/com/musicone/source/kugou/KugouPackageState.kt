package com.musicone.source.kugou

import android.content.Context
import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

internal data class KugouAccountRequest(val cookie: String, val generation: Long)
internal class KugouPackageState(val environment: SourceEnvironment,
    private val clearAccountContentMemory: () -> Unit = {}) : AutoCloseable {
    val membership = KugouMembershipClient()
    val api = KugouApiClient(membership)
    val sessions = KugouSessionClient(membership)
    val sms = KugouSmsLoginClient()
    val preferences = environment.context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
    private val lifetime = SupervisorJob()
    val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
    val revision = AtomicLong(0)
    val loginGeneration = AtomicLong(0)
    @Volatile private var closed = false
    private val lease = environment.host.registerTask { close() }
    fun cookie(): String = preferences.getString("session_kugou_credential", "").orEmpty()
    fun account(): MusicAccount? {
        val user = preferences.getString("session_kugou_user_id", "").orEmpty()
        if (kugouAuthenticatedAccountId(cookie(), user) == null) return null
        return MusicAccount(MusicSource.KUGOU, user, preferences.getString("session_kugou_nickname", "").orEmpty(),
            preferences.getString("session_kugou_avatar_url", null), membership.cached(cookie()) == true)
    }
    fun verifiedVipAccess(request: KugouAccountRequest): Boolean = membership.hasVipAccess(request.cookie).also { check(request) }
    fun capture(): KugouAccountRequest { check(!closed) { "酷狗音源已停用" }; return KugouAccountRequest(cookie(), revision.get()) }
    fun check(value: KugouAccountRequest) { check(!closed && revision.get() == value.generation && cookie() == value.cookie) { "酷狗账号已变化，旧请求已取消" } }
    suspend fun <T> request(block: suspend (KugouAccountRequest) -> T): T {
        val captured = capture()
        // 本包已声明强制登录，保存队列、缓存恢复等直接调用也不能绕过认证取源。
        requireKugouAuthenticatedRequest(captured.cookie, account()?.userId)
        return withContext(Dispatchers.IO) {
            try { check(captured); block(captured).also { currentCoroutineContext().ensureActive(); check(captured) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (blocked: KugouPlaybackBlocked) { throw SourceOperationException("AUTH_REQUIRED", blocked.message.orEmpty()) }
            catch (unavailable: TrackUnavailableException) { throw SourceOperationException("TRACK_UNAVAILABLE", unavailable.message.orEmpty()) }
            catch (failure: KugouPlaybackFailure) { throw SourceOperationException("PLAYBACK_RETRY_REQUIRED", failure.message.orEmpty(), true) }
        }
    }
    @Synchronized fun beginLogin(): Long = loginGeneration.incrementAndGet()
    @Synchronized fun save(captured: KugouAccountRequest, cookie: String, account: MusicAccount, expectedLogin: Long? = null) {
        check(captured); check(expectedLogin == null || loginGeneration.get() == expectedLogin) { "登录请求已取消" }
        val previousAccount = this.account()?.userId
        val sessionChanged = kugouSessionChanged(this.cookie(), previousAccount, cookie, account.userId, expectedLogin)
        check(preferences.edit().putString("session_kugou_credential", cookie)
            .putString("session_kugou_user_id", account.userId).putString("session_kugou_nickname", account.nickname)
            .putString("session_kugou_avatar_url", account.avatarUrl).putBoolean("session_kugou_vip_access", account.hasVipAccess).commit())
        if (sessionChanged) { revision.incrementAndGet(); loginGeneration.incrementAndGet() }
        if (previousAccount != account.userId) clearAccountContentMemory()
    }
    @Synchronized fun logout() {
        check(!closed)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("session_kugou_") }.forEach { editor.remove(it) }
        check(editor.commit()); revision.incrementAndGet(); loginGeneration.incrementAndGet()
        membership.clear()
        clearAccountContentMemory()
    }
    override fun close() { if (closed) return; closed = true; revision.incrementAndGet(); loginGeneration.incrementAndGet();
        membership.clear(); clearAccountContentMemory(); lifetime.cancel(); lease.close() }
}

internal fun requireKugouAuthenticatedRequest(cookie: String, accountId: String?) {
    if (kugouAuthenticatedAccountId(cookie, accountId) == null) {
        throw SourceOperationException("AUTH_REQUIRED", "请先登录酷狗音乐")
    }
}

/** 只核对已有完整凭证和本地账号，不联网验票；认证页与业务门禁使用相同条件。 */
internal fun kugouAuthenticatedAccountId(cookie: String, accountId: String?): String? {
    val values = cookie.cookieValues()
    return accountId?.takeIf { user -> user.toLongOrNull()?.let { it > 0 } == true &&
        values["userid"] == user && !values["token"].isNullOrBlank() && !values["KUGOU_API_MID"].isNullOrBlank() }
}
