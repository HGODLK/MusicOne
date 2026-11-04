package com.musicone.source.qq

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.source.qq.internal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class QqAuthentication(private val context: Context, private val onLoginCompleted: () -> Unit, private val motionPreference: () -> Boolean) : SourceAuthentication {
    val reduceMotion get() = motionPreference()
    // 页面仅在完整会话写入成功后退出，播放安全验证不会触发此出口。
    fun loginCompleted() = onLoginCompleted()
    // 播放验证结束只关闭认证覆盖层，不把它当作新的登录成功。
    fun verificationClosed() = onLoginCompleted()
    private val preferences = PlatformPreferences(context)
    init { QqSessionRequestCoordinator.sessionChanged(session().credential) }
    private val adapter = QqAuthAdapter(QqApiClient())
    private val revision = QqAccountRevision()
    private val challenges = ConcurrentHashMap<String, Pending>()
    @Volatile private var expired = false
    private val renewal = QqSessionRenewal(::session, QqPersistentSession(),
        save = { previous, result -> saveIfCurrent(previous, result.credential, result.account, refreshing = true) },
        onRejected = { expired = true })
    @Volatile private var verificationCredential = ""
    @Volatile private var verifiedTransition: Pair<String, String>? = null
    fun isCurrent(previous: PlatformSession): Boolean {
        val current = session().credential
        return current == previous.credential || verifiedTransition == (previous.credential to current)
    }
    private sealed interface Pending {
        data class Qr(val challenge: PlatformQrChallenge) : Pending
        data class Sms(val phone: String, val country: String, val credentialSeed: String) : Pending
    }
    override val methods = listOf(SourceLoginMethod("qr", "QQ扫码"), SourceLoginMethod("wechat-qr", "微信扫码"), SourceLoginMethod("sms", "短信登录"))
    fun session(): PlatformSession = preferences.readSession(MusicSource.QQ)
    // 会员声明只读当前会话，不能通过 readSession 启动原权益层的后台请求。
    fun membershipSession(): Pair<String, Long>? {
        if (expired) return null
        val credential = preferences.preferences.getString("session_qq_credential", "").orEmpty()
        val accountId = preferences.preferences.getString("session_qq_user_id", "").orEmpty()
        if (accountId.isBlank() || qqCredentialAccountId(credential) != accountId) return null
        return credential to revision.get()
    }
    override fun currentAccount(): SourceAccountState {
        val account = session().account
        return SourceAccountState(if (expired) SourceSessionStatus.EXPIRED else if (account != null) SourceSessionStatus.AUTHENTICATED else SourceSessionStatus.GUEST,
            account?.sdkAccount(), revision.get())
    }
    fun qrController(
        scope: CoroutineScope,
        onChanged: (PlatformQrLoginStatus, String?, PlatformQrChallenge?) -> Unit,
        onSuccess: (SourceAccountState) -> Unit,
        onSecurity: (Throwable, Boolean) -> Boolean,
    ): QqQrLoginController {
        var previous: PlatformSession? = null
        return QqQrLoginController(scope, create = { _, method, deviceId ->
            withContext(Dispatchers.IO) {
                val before = session()
                val value = adapter.createQrCode(method, deviceId)
                currentCoroutineContext().ensureActive()
                check(session().credential == before.credential) { "账号已变化，请重新登录" }
                previous = before
                value
            }
        }, check = { challenge ->
            withContext(Dispatchers.IO) {
                val value = adapter.checkQrCode(challenge)
                currentCoroutineContext().ensureActive()
                check(session().credential == previous?.credential) { "账号已变化，请重新登录" }
                value
            }
        }, onChanged = onChanged, onSuccess = { credential, account ->
            saveIfCurrent(checkNotNull(previous), credential, account)
            onSuccess(currentAccount())
        }, onSecurity = onSecurity)
    }

    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge = withContext(Dispatchers.IO) {
        val before = session()
        val id = UUID.randomUUID().toString()
        if (methodId == "sms") {
            val phone = input["phone"].orEmpty().filter(Char::isDigit)
            val country = input["countryCode"].orEmpty().ifBlank { "86" }
            require(phone.isNotBlank()) { "请输入手机号" }
            val result = adapter.sendCaptcha(phone, country, before, mergePlatformCredentials(before.credential, verificationCredential))
            currentCoroutineContext().ensureActive()
            check(session().credential == before.credential) { "账号已变化，请重试" }
            challenges[id] = Pending.Sms(phone, country, result.credentialSeed)
            SourceAuthChallenge(id, "sms", result.message)
        } else {
            val method = when(methodId) { "qr" -> PlatformLoginMethod.QR_CODE; "wechat-qr" -> PlatformLoginMethod.WECHAT_QR_CODE; else -> error("不支持的登录方式") }
            val challenge = adapter.createQrCode(method, before.deviceId)
            currentCoroutineContext().ensureActive()
            check(session().credential == before.credential) { "账号已变化，请重试" }
            challenges[id] = Pending.Qr(challenge)
            SourceAuthChallenge(id, methodId, challenge.scanMessage, challenge.url.takeIf(String::isNotBlank), challenge.imageBase64)
        }
    }
    override suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult = withContext(Dispatchers.IO) {
        val before = session()
        when (val pending = challenges[challenge.id] ?: error("登录会话已取消或过期")) {
            is Pending.Sms -> {
                val code = input["code"].orEmpty()
                require(code.isNotBlank()) { "请输入短信验证码" }
                val result = adapter.loginByCaptcha(pending.phone, pending.country, code, before, mergePlatformCredentials(pending.credentialSeed, verificationCredential))
                saveIfCurrent(before, result.credential, result.account)
                challenges.remove(challenge.id)
                SourceAuthResult(SourceAuthProgress.SUCCEEDED, "登录成功", account = result.account.sdkAccount())
            }
            is Pending.Qr -> {
                val result = adapter.checkQrCode(pending.challenge)
                currentCoroutineContext().ensureActive()
                check(session().credential == before.credential) { "账号已变化，请重新登录" }
                when (result.status) {
                    PlatformQrLoginStatus.SUCCESS -> {
                        val account = result.account ?: QqApiClient().account(result.credential)
                        saveIfCurrent(before, result.credential, account)
                        challenges.remove(challenge.id)
                        SourceAuthResult(SourceAuthProgress.SUCCEEDED, result.message, account = account.sdkAccount())
                    }
                    PlatformQrLoginStatus.EXPIRED -> { challenges.remove(challenge.id); SourceAuthResult(SourceAuthProgress.EXPIRED, result.message) }
                    PlatformQrLoginStatus.FAILED -> throw SourceOperationException("qq-login-failed", result.message, true)
                    PlatformQrLoginStatus.WAITING_CONFIRM -> SourceAuthResult(SourceAuthProgress.CONFIRMATION_REQUIRED, result.message, challenge)
                    else -> SourceAuthResult(SourceAuthProgress.WAITING, result.message, challenge)
                }
            }
        }
    }
    private suspend fun saveIfCurrent(previous: PlatformSession, credential: String, account: MusicAccount, refreshing: Boolean = false) {
        currentCoroutineContext().ensureActive()
        check(session().credential == previous.credential) { "账号已变化，旧登录结果已取消" }
        preferences.saveSession(credential, account)
        if (!refreshing || previous.credential != credential) QqSessionRequestCoordinator.sessionChanged(credential)
        revision.saved(previous.account, account, refreshing, expired)
        expired = false
    }
    // QQ续期失败保留原凭证，网络故障不能触发清空迁移数据。
    override suspend fun refreshAccount(): SourceAccountState = withContext(Dispatchers.IO) {
        renewal.validate()
        currentAccount()
    }
    suspend fun prepareRequest() = withContext(Dispatchers.IO) { renewal.prepare() }
    suspend fun renewRejected(rejected: PlatformSession): PlatformSession? = withContext(Dispatchers.IO) { renewal.renewRejected(rejected) }
    // 定时保活只处理快到期的票；已确认失效时等用户重新登录或宿主校验，不在后台反复请求。
    suspend fun renewIfDue() { if (!expired) prepareRequest() }
    override suspend fun logout() {
        challenges.clear()
        verificationCredential = ""
        verifiedTransition = null
        QqSessionRequestCoordinator.sessionChanged("")
        preferences.clearSession(MusicSource.QQ)
        expired = false
        revision.incrementAndGet()
    }
    override fun exportSession(): SourceSessionSnapshot? {
        val values = preferences.preferences.all.filterKeys { it.startsWith("session_qq_") }.mapValues { it.value.toString() }
        return if (values.isEmpty()) null else SourceSessionSnapshot("qq.platform-settings", 1, values)
    }
    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt {
        require(session.sourceId == QQ_SOURCE_ID)
        val markers = context.getSharedPreferences("qq_session_migrations", Context.MODE_PRIVATE)
        if (markers.getBoolean(session.migrationId, false)) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.ALREADY_IMPORTED)
        val target = preferences.preferences
        if (target.contains("session_qq_credential")) {
            check(markers.edit().putBoolean(session.migrationId, true).commit())
            return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.EXISTING_SESSION_PRESERVED)
        }
        val old = session.preferenceNamespaces["platform_settings"].orEmpty().filterKeys { it.startsWith("session_qq_") }
        if (old["session_qq_credential"].isNullOrBlank()) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.EXISTING_SESSION_PRESERVED)
        val editor = target.edit()
        old.forEach { (key, value) -> when {
            key.endsWith("_membership_version") || key.endsWith("_follows") || key.endsWith("_followers") -> editor.putInt(key, value.toInt())
            key.endsWith("_vip_access") -> editor.putBoolean(key, value.toBooleanStrict())
            else -> editor.putString(key, value)
        } }
        check(editor.commit()) { "QQ完整旧会话写入失败" }
        check(old.all { (key, value) -> target.all[key].toString() == value }) { "QQ旧会话校验失败" }
        check(markers.edit().putBoolean(session.migrationId, true).commit())
        QqSessionRequestCoordinator.sessionChanged(session().credential)
        revision.incrementAndGet()
        return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.IMPORTED)
    }
    fun cancelChallenges() { challenges.clear() }
    fun completeVerification(challenge: PlatformSecurityChallenge, result: PlatformSecurityVerificationResult): String {
        val credential = adapter.completeSecurityVerification(challenge, result)
        verificationCredential = mergePlatformCredentials(verificationCredential, credential)
        challenges.replaceAll { _, pending -> when (pending) {
            is Pending.Qr -> pending.copy(challenge = pending.challenge.copy(verificationCredential = mergePlatformCredentials(pending.challenge.verificationCredential, credential)))
            is Pending.Sms -> pending.copy(credentialSeed = mergePlatformCredentials(pending.credentialSeed, credential))
        } }
        val current = session()
        // 仅把同一账号验证返回的 Cookie 合并回原完整会话。
        if (current.account != null && qqCredentialAccountId(current.credential) == qqCredentialAccountId(challenge.credentialSeed)) {
            val merged = mergePlatformCredentials(current.credential, credential)
            preferences.saveSession(merged, current.account)
            verifiedTransition = current.credential to merged
            revision.incrementAndGet()
        }
        return credential
    }
    fun close() { challenges.clear(); QqSessionRequestCoordinator.sessionChanged(""); QqEntitlements.forget(session().credential) }
}
