package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多台服务器共用一个音源：每台服务器加用户名是一个独立账号，切换即更换当前账号并递增版本号，
 * 宿主据此重读资料。已保存的服务器保留 token，切回时不需要再输密码。
 */
internal class SubsonicAuthentication(
    private val store: SubsonicServerStore,
    private val client: SubsonicClient,
    private val onLoginCompleted: () -> Unit,
    private val motionPreference: () -> Boolean,
) : SourceAuthentication {
    val reduceMotion: Boolean get() = motionPreference()
    override val methods = listOf(SourceLoginMethod("password", "服务器账号"))

    fun current(): SubsonicServer? = store.snapshot().current
    fun servers(): List<SubsonicServer> = store.snapshot().servers.sortedBy { it.addedAtMs }
    fun server(key: String?): SubsonicServer? = key?.let(store::server)
    val revision: Long get() = store.snapshot().revision

    override fun currentAccount(): SourceAccountState {
        val state = store.snapshot()
        val server = state.current
        val status = when {
            server == null -> SourceSessionStatus.GUEST
            server.expired -> SourceSessionStatus.EXPIRED
            else -> SourceSessionStatus.AUTHENTICATED
        }
        return SourceAccountState(status, server?.account(), state.revision)
    }

    /** 校验地址与账号后保存并切换；失败时不改动已保存的服务器。 */
    suspend fun connect(address: String, username: String, password: String): SubsonicServer = withContext(Dispatchers.IO) {
        val baseUrl = normalizeSubsonicUrl(address)
        val user = username.trim()
        require(user.isNotEmpty()) { "请输入用户名" }
        require(password.isNotEmpty()) { "请输入密码" }
        val salt = subsonicSalt()
        val candidate = SubsonicServer(subsonicServerKey(baseUrl, user), baseUrl, user, salt, subsonicToken(password, salt),
            addedAtMs = store.server(subsonicServerKey(baseUrl, user))?.addedAtMs ?: System.currentTimeMillis())
        val (verified, ping) = try { candidate to client.get(candidate, "ping") }
        catch (error: SubsonicApiException) {
            // 部分服务器（如使用 LDAP 的 Airsonic）不支持 token，只能改用编码后的密码。
            if (error.code != 41) throw error
            val legacy = candidate.copy(legacyPasswordHex = hexEncode(password))
            legacy to client.get(legacy, "ping")
        }
        val openSubsonic = ping.optBoolean("openSubsonic")
        val extensions = if (openSubsonic) runCatching { readExtensions(verified) }.getOrDefault(emptySet()) else emptySet()
        val saved = verified.copy(serverType = ping.optString("type"), serverVersion = ping.optString("serverVersion"),
            openSubsonic = openSubsonic, extensions = extensions)
        store.saveAndSelect(saved)
        saved
    }

    fun switchTo(key: String) = store.select(key)
    fun remove(key: String) = store.remove(key)
    fun loginCompleted() = onLoginCompleted()

    fun markExpired(key: String) = store.setExpired(key, true)

    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge {
        require(methodId == "password") { "不支持的登录方式" }
        val server = connect(input["url"].orEmpty(), input["username"].orEmpty(), input["password"].orEmpty())
        return SourceAuthChallenge("subsonic-${server.key}", "connected", "已连接 ${server.displayName}")
    }

    override suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult {
        val server = current()
        return if (challenge.kind == "connected" && server != null && challenge.id == "subsonic-${server.key}")
            SourceAuthResult(SourceAuthProgress.SUCCEEDED, "已连接", account = server.account())
        else SourceAuthResult(SourceAuthProgress.EXPIRED, "登录状态已变化，请重新连接")
    }

    /** 只有服务器明确拒绝账号才标记失效，网络失败保留原状态。 */
    override suspend fun refreshAccount(): SourceAccountState = withContext(Dispatchers.IO) {
        val server = current() ?: return@withContext currentAccount()
        try {
            val ping = client.get(server, "ping")
            store.setExpired(server.key, false)
            val openSubsonic = ping.optBoolean("openSubsonic")
            val extensions = if (openSubsonic) runCatching { readExtensions(server) }.getOrDefault(server.extensions) else emptySet()
            store.updateCapabilities(server.key, ping.optString("type"), ping.optString("serverVersion"), openSubsonic, extensions)
        } catch (error: SubsonicApiException) {
            if (error.code == 40) store.setExpired(server.key, true)
        } catch (_: SubsonicTransportException) { }
        currentAccount()
    }

    override suspend fun logout() { current()?.let { store.remove(it.key) } }

    override fun exportSession(): SourceSessionSnapshot? {
        val state = store.snapshot()
        if (state.servers.isEmpty()) return null
        val servers = JSONArray(state.servers.map { server ->
            JSONObject().put("baseUrl", server.baseUrl).put("username", server.username)
                .put("salt", server.salt).put("token", server.token).put("legacyPasswordHex", server.legacyPasswordHex ?: "")
        })
        return SourceSessionSnapshot("subsonic.servers", 1, mapOf("servers" to servers.toString(), "current" to state.currentKey.orEmpty()))
    }

    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt =
        throw UnsupportedOperationException("Subsonic 音源没有旧版会话可迁移")

    private fun readExtensions(server: SubsonicServer): Set<String> =
        client.get(server, "getOpenSubsonicExtensions").objects("openSubsonicExtensions")
            .map { it.optString("name") }.filter(String::isNotBlank).toSet()
}
