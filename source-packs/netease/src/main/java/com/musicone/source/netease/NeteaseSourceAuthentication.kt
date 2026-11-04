package com.musicone.source.netease

import android.content.Context
import com.musicone.source.sdk.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

private data class PendingNeteaseLogin(val kind: String, val revision: Long, val generation: Long,
    val cookie: String, val key: String = "", val country: String = "86", val phone: String = "")

internal class NeteaseSourceAuthentication(private val state: () -> NeteasePackageState) : SourceAuthentication {
    private val pending = ConcurrentHashMap<String, PendingNeteaseLogin>()
    override val methods = listOf(SourceLoginMethod("qr", "扫码登录"), SourceLoginMethod("sms", "短信登录"))
    override fun currentAccount() = state().currentAccount()
    fun cancel() { pending.clear(); state().loginGeneration.incrementAndGet() }
    private fun createChallenge(value: PendingNeteaseLogin, message: String, url: String? = null): SourceAuthChallenge =
        SourceAuthChallenge(UUID.randomUUID().toString().also { pending[it] = value }, value.kind, message, displayUrl = url)
    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge = withContext(Dispatchers.IO) {
        val owner = state(); owner.check(); val revision = owner.revision.get(); val generation = owner.loginGeneration.incrementAndGet(); pending.clear()
        val base = PendingNeteaseLogin(methodId, revision, generation, "", country = input["countryCode"].orEmpty().ifBlank { "86" }, phone = input["phone"].orEmpty())
        try {
            val next = when (methodId) {
                "qr" -> owner.api.createQrCode().let { qr -> createChallenge(base.copy(cookie = qr.cookie, key = qr.key), "请使用网易云音乐扫码", qr.url) }
                "sms" -> owner.api.sendCaptcha(base.phone, base.country, owner.deviceId()).let { createChallenge(base.copy(cookie = it.cookie), it.message) }
                else -> error("不支持此登录方式")
            }
            currentCoroutineContext().ensureActive(); owner.check(revision); check(owner.loginGeneration.get() == generation) { "登录已取消" }; next
        } catch (verification: NeteaseSecurityVerificationRequired) {
            owner.check(revision); createChallenge(base.copy(kind = "web", cookie = verification.challenge.credentialSeed), verification.message.orEmpty(), verification.challenge.url)
        }
    }
    override suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult = withContext(Dispatchers.IO) {
        val login = requireNotNull(pending[challenge.id]) { "登录挑战已失效" }; val owner = state()
        owner.check(login.revision); check(owner.loginGeneration.get() == login.generation) { "登录已取消" }
        try {
            val result = when (login.kind) {
                "qr" -> {
                    val poll = owner.api.checkQrCode(login.key, login.cookie)
                    pending[challenge.id] = login.copy(cookie = poll.cookie)
                    when (poll.status) {
                        NeteaseQrLoginStatus.SUCCESS -> {
                            val account = requireNotNull(poll.account); currentCoroutineContext().ensureActive()
                            owner.saveSession(poll.cookie, account, login.revision, login.generation); pending.clear()
                            SourceAuthResult(SourceAuthProgress.SUCCEEDED, "登录成功", account = account.toSourceAccount())
                        }
                        NeteaseQrLoginStatus.EXPIRED -> { pending.remove(challenge.id); SourceAuthResult(SourceAuthProgress.EXPIRED, poll.message) }
                        NeteaseQrLoginStatus.WAITING_CONFIRM -> SourceAuthResult(SourceAuthProgress.CONFIRMATION_REQUIRED, poll.message, challenge)
                        else -> SourceAuthResult(SourceAuthProgress.WAITING, poll.message, challenge)
                    }
                }
                "sms", "web" -> {
                    val loginResult = if (login.kind == "sms") owner.api.loginByCaptcha(login.phone, login.country,
                        input["code"].orEmpty(), owner.deviceId(), login.cookie)
                    else {
                        val cookie = mergeCookies(login.cookie, input["cookie"].orEmpty())
                        require(neteaseHasLoginCookie(cookie)) { "尚未取得真实登录 Cookie" }
                        NeteaseLoginResult(cookie, owner.api.account(cookie))
                    }
                    currentCoroutineContext().ensureActive(); owner.saveSession(loginResult.cookie, loginResult.account, login.revision, login.generation)
                    pending.clear(); SourceAuthResult(SourceAuthProgress.SUCCEEDED, "登录成功", account = loginResult.account.toSourceAccount())
                }
                else -> error("未知登录挑战")
            }
            currentCoroutineContext().ensureActive()
            if (result.progress != SourceAuthProgress.SUCCEEDED) { owner.check(login.revision); check(owner.loginGeneration.get() == login.generation) }
            result
        } catch (verification: NeteaseSecurityVerificationRequired) {
            owner.check(login.revision)
            val next = createChallenge(login.copy(kind = "web", cookie = verification.challenge.credentialSeed), verification.message.orEmpty(), verification.challenge.url)
            pending.remove(challenge.id); SourceAuthResult(SourceAuthProgress.VERIFICATION_REQUIRED, next.message, next)
        }
    }
    override suspend fun refreshAccount(): SourceAccountState {
        val owner = state(); val revision = owner.revision.get(); val cookie = owner.credential()
        if (cookie.isBlank()) return owner.currentAccount()
        try {
            val value = withContext(Dispatchers.IO) { owner.api.account(cookie) }
            currentCoroutineContext().ensureActive(); owner.saveSession(cookie, value, revision, refreshing = true)
        } catch (error: NeteaseApiException) {
            owner.check(revision)
            if (error.apiCode in setOf(301, 302)) owner.expired = true else throw error
        }
        // 请求超时、断网及未知接口错误不会清空旧会话。
        return owner.currentAccount()
    }
    override suspend fun logout() { cancel(); state().logout() }
    override fun exportSession(): SourceSessionSnapshot? {
        val owner = state(); if (owner.credential().isBlank()) return null
        val values = owner.environment.context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("session_netease_") }.mapValues { it.value.toString() }
        return SourceSessionSnapshot("netease.full-session", 1, values)
    }
    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt {
        require(session.sourceId == NETEASE_SOURCE_ID); val owner = state(); owner.check()
        val context = owner.environment.context
        val markers = context.getSharedPreferences("source_migrations", Context.MODE_PRIVATE)
        if (markers.getBoolean(session.migrationId, false)) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.ALREADY_IMPORTED)
        val preferences = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        if (preferences.contains("session_netease_credential")) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.EXISTING_SESSION_PRESERVED)
        val source = session.preferenceNamespaces["platform_settings"].orEmpty()
        val values = source.filterKeys { it.startsWith("session_netease_") }.toMutableMap()
        mapOf("netease_cookie" to "credential", "netease_device_id" to "device_id", "user_id" to "user_id", "nickname" to "nickname", "avatar_url" to "avatar_url").forEach { (old, field) ->
            source[old]?.let { values.putIfAbsent("session_netease_$field", it) }
        }
        require(!values["session_netease_credential"].isNullOrBlank()) { "旧会话缺少完整 Cookie" }
        val edit = preferences.edit()
        values.forEach { (key, value) -> when {
            key.endsWith("_vip_access") -> edit.putBoolean(key, value.toBoolean())
            key.endsWith("_follows") || key.endsWith("_followers") -> edit.putInt(key, value.toIntOrNull() ?: 0)
            else -> edit.putString(key, value)
        } }
        session.privateFiles.forEach { (name, bytes) ->
            require(name.startsWith("profile/netease_") && !name.contains("..") && name.endsWith(".webp"))
            val target = java.io.File(context.filesDir, name)
            if (!target.exists()) {
                target.parentFile!!.mkdirs()
                val temporary = java.io.File(target.parentFile, "${target.name}.migration")
                temporary.writeBytes(bytes)
                check(temporary.length() == bytes.size.toLong() && temporary.renameTo(target)) { "背景迁移失败，原输入仍保留" }
            }
        }
        check(edit.commit()) { "旧会话持久化失败，输入仍保留" }
        check(markers.edit().putBoolean(session.migrationId, true).commit()); owner.reloadSession()
        return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.IMPORTED)
    }
}
