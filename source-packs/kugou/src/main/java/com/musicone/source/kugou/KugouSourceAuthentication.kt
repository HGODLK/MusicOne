package com.musicone.source.kugou

import android.content.Context
import com.musicone.source.sdk.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class KugouPendingLogin(val request: KugouAccountRequest, val generation: Long, val kind: String,
    val cookie: String, val key: String = "", val country: String = "86", val phone: String = "",
    val code: String = "", val security: PlatformSecurityChallenge? = null, val retryAction: String = "send")

internal class KugouSourceAuthentication(private val state: () -> KugouPackageState) : SourceAuthentication {
    private val pending = ConcurrentHashMap<String, KugouPendingLogin>()
    private var verifiedSeed = ""
    override val methods = listOf(SourceLoginMethod("qr", "扫码登录"), SourceLoginMethod("sms", "手机号验证码登录"))
    fun cancel() { pending.clear(); state().beginLogin() }
    fun security(challenge: SourceAuthChallenge): PlatformSecurityVerificationUiState? = pending[challenge.id]?.security?.toUiState()
    override fun currentAccount() = SourceAccountState(if (state().account() == null) SourceSessionStatus.GUEST else SourceSessionStatus.AUTHENTICATED,
        state().account()?.let(KugouSourceMapping::account), state().revision.get())
    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge = withContext(Dispatchers.IO) {
        val owner = state(); val captured = owner.capture(); val generation = owner.beginLogin(); pending.clear()
        val next = if (methodId == "qr") {
            val qr = owner.api.createQrCode()
            create(KugouPendingLogin(captured, generation, "qr", qr.deviceCookie, qr.key), "请使用酷狗音乐扫码", qr.url)
        } else {
            require(methodId == "sms") { "不支持此登录方式" }
            val phone = requireNotNull(input["phone"]?.takeIf(String::isNotBlank)) { "请输入手机号" }
            val base = KugouPendingLogin(captured, generation, "sms", kugouLoginDeviceCookie(verifiedSeed.ifBlank { captured.cookie }),
                country = input["countryCode"].orEmpty().ifBlank { "86" }, phone = phone)
            send(base)
        }
        currentCoroutineContext().ensureActive(); owner.check(captured); check(owner.loginGeneration.get() == generation)
        next
    }
    private fun send(base: KugouPendingLogin): SourceAuthChallenge = try {
        val result = state().sms.sendCaptcha(base.phone, base.country, base.cookie)
        requireCurrent(base)
        verifiedSeed = result.deviceCookie
        create(base.copy(kind = "sms", cookie = result.deviceCookie, security = null), result.message)
    } catch (verification: PlatformSecurityVerificationRequired) {
        requireCurrent(base)
        verifiedSeed = verification.challenge.credentialSeed
        create(base.copy(kind = "verification", cookie = verifiedSeed, security = verification.challenge, retryAction = "send"), verification.message.orEmpty())
    } catch (failure: KugouSmsRequestException) { requireCurrent(base); verifiedSeed = failure.credentialSeed; throw failure }

    override suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult = withContext(Dispatchers.IO) {
        val owner = state(); val value = requireNotNull(pending[challenge.id]) { "登录挑战已失效，请重新开始" }
        owner.check(value.request); check(owner.loginGeneration.get() == value.generation) { "登录请求已取消" }
        val result = when (value.kind) {
            "qr" -> {
                val result = owner.api.checkQrCode(value.key, value.cookie)
                when (result.status) {
                    PlatformQrLoginStatus.SUCCESS -> {
                        val account = requireNotNull(result.account) { "平台没有返回完整账号" }
                        currentCoroutineContext().ensureActive(); owner.save(value.request, result.cookie, account, value.generation); pending.clear()
                        verifiedSeed = result.cookie
                        SourceAuthResult(SourceAuthProgress.SUCCEEDED, result.message, account = KugouSourceMapping.account(account))
                    }
                    PlatformQrLoginStatus.EXPIRED -> { pending.remove(challenge.id); SourceAuthResult(SourceAuthProgress.EXPIRED, result.message) }
                    PlatformQrLoginStatus.FAILED -> throw PlatformApiException(result.message)
                    else -> SourceAuthResult(if (result.status == PlatformQrLoginStatus.WAITING_CONFIRM) SourceAuthProgress.CONFIRMATION_REQUIRED else SourceAuthProgress.WAITING, result.message, challenge)
                }
            }
            "verification" -> {
                val security = requireNotNull(value.security)
                val proof = proof(input)
                val cookie = owner.sms.completeSecurityVerification(security, proof)
                verifiedSeed = cookie
                if (security.isKugouAccountRecovery()) {
                    pending.remove(challenge.id)
                    SourceAuthResult(SourceAuthProgress.WAITING, "请确认账号已恢复，再重新获取短信验证码登录")
                } else if (value.retryAction == "login") login(value.copy(kind = "sms", cookie = cookie, security = null), value.code)
                else {
                    val next = send(value.copy(kind = "sms", cookie = cookie, security = null))
                    pending.remove(challenge.id)
                    SourceAuthResult(if (next.kind == "verification") SourceAuthProgress.VERIFICATION_REQUIRED else SourceAuthProgress.WAITING, next.message, next)
                }
            }
            "sms" -> login(value, input["code"].orEmpty())
            else -> error("未知登录挑战")
        }
        currentCoroutineContext().ensureActive()
        if (result.progress != SourceAuthProgress.SUCCEEDED) { owner.check(value.request); check(owner.loginGeneration.get() == value.generation) }
        result
    }
    private suspend fun login(value: KugouPendingLogin, code: String): SourceAuthResult = try {
        val result = state().sms.loginByCaptcha(value.phone, value.country, code, value.cookie)
        val account = result.account.copy(hasVipAccess = state().membership.hasVipAccess(result.cookie))
        currentCoroutineContext().ensureActive(); state().save(value.request, result.cookie, account, value.generation); pending.clear()
        verifiedSeed = result.cookie
        SourceAuthResult(SourceAuthProgress.SUCCEEDED, "登录成功", account = KugouSourceMapping.account(account))
    } catch (verification: PlatformSecurityVerificationRequired) {
        requireCurrent(value)
        verifiedSeed = verification.challenge.credentialSeed
        val next = create(value.copy(kind = "verification", cookie = verifiedSeed, code = code,
            security = verification.challenge, retryAction = "login"), verification.message.orEmpty())
        SourceAuthResult(SourceAuthProgress.VERIFICATION_REQUIRED, next.message, next)
    } catch (failure: KugouSmsRequestException) { requireCurrent(value); verifiedSeed = failure.credentialSeed; throw failure }

    private fun requireCurrent(value: KugouPendingLogin) {
        state().check(value.request)
        check(state().loginGeneration.get() == value.generation) { "登录请求已取消" }
    }

    private fun create(value: KugouPendingLogin, message: String, url: String? = null): SourceAuthChallenge {
        val id = UUID.randomUUID().toString(); pending[id] = value
        return SourceAuthChallenge(id, value.kind, message, displayUrl = url,
            payload = value.security?.let { SourcePayload("kugou.security", 1, JSONObject().put("kind", it.kind.name)
                .put("title", it.title).put("message", it.message).put("url", it.url).put("appId", it.appId).toString()) } ?: SourcePayload())
    }
    override suspend fun refreshAccount(): SourceAccountState {
        val owner = state(); val captured = owner.capture()
        if (captured.cookie.isBlank()) return currentAccount()
        val account = withContext(Dispatchers.IO) { owner.api.account(captured.cookie) }
        currentCoroutineContext().ensureActive(); owner.save(captured, captured.cookie, account)
        return currentAccount()
    }
    override suspend fun logout() { cancel(); verifiedSeed = ""; state().logout() }
    override fun exportSession(): SourceSessionSnapshot? {
        if (state().cookie().isBlank()) return null
        return SourceSessionSnapshot("kugou.full-session", 1, state().preferences.all.filterKeys { it.startsWith("session_kugou_") }
            .mapValues { it.value?.toString().orEmpty() })
    }
    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt {
        require(session.sourceId == KUGOU_SOURCE_ID)
        val owner = state(); val markers = owner.environment.context.getSharedPreferences("source_migrations", Context.MODE_PRIVATE)
        if (markers.getBoolean(session.migrationId, false)) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.ALREADY_IMPORTED)
        if (owner.cookie().isNotBlank()) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.EXISTING_SESSION_PRESERVED)
        val values = session.preferenceNamespaces["platform_settings"].orEmpty().filterKeys { it.startsWith("session_kugou_") }
        require(!values["session_kugou_credential"].isNullOrBlank()) { "旧会话缺少完整凭证" }
        val editor = owner.preferences.edit()
        values.forEach { (key, value) -> if (key.endsWith("vip_access")) editor.putBoolean(key, value.toBoolean()) else editor.putString(key, value) }
        check(editor.commit()); check(markers.edit().putBoolean(session.migrationId, true).commit()); owner.revision.incrementAndGet()
        return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.IMPORTED)
    }
    private fun proof(input: Map<String, String>): PlatformSecurityVerificationResult {
        val points = input["touchPoints"]?.let(::JSONArray)
        return PlatformSecurityVerificationResult(input["payload"].orEmpty(), input["cookie"].orEmpty(),
            (0 until (points?.length() ?: 0)).map { index -> points!!.getJSONObject(index).let { PlatformTouchPoint(it.getLong("time"), it.getInt("x"), it.getInt("y")) } },
            input["viewportWidth"]?.toIntOrNull() ?: 0, input["viewportHeight"]?.toIntOrNull() ?: 0)
    }
    fun proofInput(result: PlatformSecurityVerificationResult): Map<String, String> = mapOf("payload" to result.payload, "cookie" to result.cookie,
        "viewportWidth" to result.viewportWidth.toString(), "viewportHeight" to result.viewportHeight.toString(),
        "touchPoints" to JSONArray().apply { result.touchPoints.forEach { put(JSONObject().put("time", it.elapsedMs).put("x", it.x).put("y", it.y)) } }.toString())
}
