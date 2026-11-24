package com.musicone.source.webdav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*
import java.util.concurrent.atomic.AtomicLong

/**
 * WebDAV 认证与多服务器管理服务。
 * 支持保存、切换、删除多个 WebDAV 服务器，以及匿名访问（用户名/密码可留空）。
 */
internal class WebDavAuthentication(
    private val store: WebDavStore,
    private val client: WebDavClient,
    private val onLoginCompleted: () -> Unit,
    private val motionPreference: () -> Boolean,
) : SourceAuthentication {
    private val revision = AtomicLong(1L)

    /** 账号修订的可观察副本：页面须在子组件使用旧列表状态前先随账号重组。 */
    var observedRevision by mutableLongStateOf(1L)
        private set

    override val methods: List<SourceLoginMethod> = listOf(
        SourceLoginMethod("webdav_server", "WebDAV 服务器"),
    )

    val reduceMotion: Boolean get() = motionPreference()

    fun servers(): List<WebDavServer> = store.allServers()

    fun currentServer(): WebDavServer? = store.activeServer()

    fun findServer(serverKey: String): WebDavServer? = store.findServer(serverKey)

    fun loginCompleted() = onLoginCompleted()

    override fun currentAccount(): SourceAccountState {
        val active = store.activeServer() ?: return SourceAccountState(SourceSessionStatus.GUEST, null, revision.get())
        val status = if (active.expired) SourceSessionStatus.EXPIRED else SourceSessionStatus.AUTHENTICATED
        return SourceAccountState(status, active.toSourceAccount(), revision.get())
    }

    /** 连接并校验 WebDAV 服务器（PROPFIND Depth: 0），通过后持久化并设为当前活跃服务器。 */
    suspend fun connect(
        rawAddress: String,
        rawRootPath: String,
        rawUsername: String,
        password: String,
        rawDisplayName: String = "",
    ): WebDavServer {
        val baseUrl = WebDavPaths.normalizeBaseUrl(rawAddress)
        val rootPath = WebDavPaths.normalizeRelativePath(rawRootPath)
        val username = rawUsername.trim()
        val key = WebDavIdentity.serverKey(baseUrl, rootPath, username)
        val displayName = rawDisplayName.trim().ifEmpty { deriveServerName(baseUrl, rootPath, username) }
        val candidate = WebDavServer(
            key = key,
            displayName = displayName,
            baseUrl = baseUrl,
            rootPath = rootPath,
            username = username,
            password = password,
            expired = false,
        )
        client.clearAuth(key)
        client.testConnection(candidate)
        store.upsertAndSelectServer(candidate)
        advanceRevision()
        return candidate
    }

    fun switchTo(serverKey: String) {
        val target = store.selectServer(serverKey) ?: throw SourceOperationException("NOT_FOUND", "服务器配置不存在")
        client.clearAuth(target.key)
        advanceRevision()
    }

    fun removeServer(serverKey: String) {
        client.clearAuth(serverKey)
        store.removeServer(serverKey)
        advanceRevision()
    }

    fun markExpired(serverKey: String) {
        store.markExpired(serverKey, true)
        advanceRevision()
    }

    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge {
        require(methodId == "webdav_server") { "不支持的认证方式：$methodId" }
        return SourceAuthChallenge(
            id = "webdav-${System.currentTimeMillis()}",
            kind = "webdav_credentials",
            message = "请输入 WebDAV 服务器地址和凭据",
        )
    }

    override suspend fun continueAuthentication(
        challenge: SourceAuthChallenge,
        input: Map<String, String>,
    ): SourceAuthResult {
        val server = connect(
            rawAddress = input["address"].orEmpty(),
            rawRootPath = input["rootPath"].orEmpty(),
            rawUsername = input["username"].orEmpty(),
            password = input["password"].orEmpty(),
            rawDisplayName = input["displayName"].orEmpty(),
        )
        return SourceAuthResult(SourceAuthProgress.SUCCEEDED, "已连接 ${server.displayName}", account = server.toSourceAccount())
    }

    override suspend fun refreshAccount(): SourceAccountState {
        val active = store.activeServer() ?: return currentAccount()
        try {
            client.testConnection(active)
            if (active.expired) {
                store.markExpired(active.key, false)
                advanceRevision()
            }
        } catch (error: SourceOperationException) {
            if (error.code == "AUTH_EXPIRED") {
                markExpired(active.key)
            }
            // 网络临时错误不清空已保存凭据
        }
        return currentAccount()
    }

    override suspend fun logout() {
        store.activeServer()?.let { client.clearAuth(it.key) }
        store.clearActiveServer()
        advanceRevision()
    }

    override fun exportSession(): SourceSessionSnapshot? {
        if (store.allServers().isEmpty()) return null
        return SourceSessionSnapshot(
            format = "musicone.webdav.session",
            version = 1,
            fields = mapOf("serversJson" to store.exportServersJson()),
        )
    }

    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt {
        val raw = session.preferenceNamespaces["webdav"]?.get("serversJson").orEmpty()
        if (store.allServers().isNotEmpty()) {
            return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.EXISTING_SESSION_PRESERVED)
        }
        val imported = store.importServersJsonIfEmpty(raw)
        if (imported) advanceRevision()
        return SourceMigrationReceipt(
            migrationId = session.migrationId,
            status = if (imported) SourceMigrationStatus.IMPORTED else SourceMigrationStatus.ALREADY_IMPORTED,
        )
    }

    private fun advanceRevision() {
        observedRevision = revision.incrementAndGet()
    }

    private fun WebDavServer.toSourceAccount(): SourceAccount = SourceAccount(
        id = accountId,
        displayName = displayName,
        signature = if (username.isNotEmpty()) "$username · $subtitleUrl" else subtitleUrl,
    )

    private fun deriveServerName(baseUrl: String, rootPath: String, username: String): String {
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull().orEmpty().ifBlank { baseUrl }
        val tail = WebDavPaths.fileNameOf(rootPath)
        return buildString {
            append(host)
            if (tail.isNotEmpty()) append(" / ").append(tail)
            else if (username.isNotEmpty()) append(" (").append(username).append(')')
        }
    }
}
