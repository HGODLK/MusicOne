package com.musicone.source.bilibili

import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

internal const val BILI_SOURCE_ID = "com.musicone.source.bilibili"
internal const val BILI_PAGE_COLOR = 0xFFFB7299L
internal data class BiliAccountRequest(val session: BiliAccountSession?, val generation: Long)

internal class BiliPackageState(val environment: SourceEnvironment) : AutoCloseable {
    val api = BiliApi(environment.context)
    val sessions = BiliSessionStore(environment.context)
    val webSession = BiliWebSession(environment.context)
    val cache = BiliFolderCache(environment.context)
    private val lifetime = SupervisorJob()
    val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
    val folders = BiliFolderRepository(cache) { account, folder, page, query -> api.getFavoriteVideos(folder, page, account.cookie, query) }
    val directory = BiliFolderDirectory(cache, scope,
        { account -> withContext(Dispatchers.IO) { api.getFavoriteFolders(account.mid.toLong(), account.cookie) } },
        { account, folder -> withContext(Dispatchers.IO) { api.getFavoriteFolderCover(folder, account.cookie) } })
    val revision = AtomicLong(0)
    val authGeneration = AtomicLong(0)
    @Volatile var expired = false
    @Volatile private var closed = false
    private val taskLease = environment.host.registerTask { close() }

    fun capture(): BiliAccountRequest { check(!closed) { "哔哩哔哩音源已停用" }; return BiliAccountRequest(sessions.read(), revision.get()) }
    fun check(request: BiliAccountRequest) {
        check(!closed && revision.get() == request.generation && sessions.read() == request.session) { "哔哩哔哩账号已变化，已取消旧请求" }
    }
    suspend fun <T> request(loginRequired: Boolean = false, block: suspend (BiliAccountRequest) -> T): T {
        val request = capture()
        require(!loginRequired || request.session != null) { "请先登录哔哩哔哩账号" }
        return withContext(Dispatchers.IO) {
            try { block(request).also { currentCoroutineContext().ensureActive(); check(request) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: PlatformApiException) {
                throw SourceOperationException(if (failure.apiCode in setOf(-101, -111, -403, -412, 403, 429)) "AUTH_REQUIRED" else "PLAYBACK_RETRY_REQUIRED",
                    failure.message.orEmpty(), failure.apiCode !in setOf(-101, -111, -403, -412, 403, 429))
            }
        }
    }
    @Synchronized fun nextLoginGeneration(): Long = authGeneration.incrementAndGet()
    @Synchronized fun saveSession(request: BiliAccountRequest, session: BiliAccountSession, loginGeneration: Long? = null) {
        check(request)
        check(loginGeneration == null || authGeneration.get() == loginGeneration) { "登录请求已取消" }
        sessions.save(session)
        revision.incrementAndGet(); authGeneration.incrementAndGet(); expired = false
        folders.clear(); directory.attach(session)
    }
    @Synchronized fun logout() {
        check(!closed)
        revision.incrementAndGet(); authGeneration.incrementAndGet()
        sessions.clear(); expired = false; folders.clear(); directory.attach(null)
    }
    fun account(): SourceAccountState {
        val session = sessions.read()
        return SourceAccountState(when { session == null -> SourceSessionStatus.GUEST; expired -> SourceSessionStatus.EXPIRED; else -> SourceSessionStatus.AUTHENTICATED },
            session?.summary(), revision.get())
    }
    override fun close() {
        if (closed) return
        closed = true; revision.incrementAndGet(); authGeneration.incrementAndGet()
        lifetime.cancel(); directory.cancel(); folders.clear(); taskLease.close()
    }
}

internal fun BiliAccountSession.summary() = SourceAccount(mid, name, avatar.takeIf(String::isNotBlank))
