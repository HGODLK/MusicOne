package com.musicone.source.bilibili

import android.content.Context
import com.musicone.source.sdk.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class BiliPendingLogin(val request: BiliAccountRequest, val generation: Long, val kind: String,
    val key: String = "", val country: String = "86", val phone: String = "", val verification: BiliGeetestChallenge? = null)

internal class BiliSourceAuthentication(private val state: () -> BiliPackageState) : SourceAuthentication {
    private val pending = ConcurrentHashMap<String, BiliPendingLogin>()
    override val methods = listOf(SourceLoginMethod("qr", "扫码登录"), SourceLoginMethod("sms", "短信登录"))
    override fun currentAccount(): SourceAccountState = state().account()
    fun cancel() { pending.clear(); state().nextLoginGeneration() }
    fun verification(challenge: SourceAuthChallenge): BiliGeetestChallenge? = pending[challenge.id]?.verification
    override suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge = withContext(Dispatchers.IO) {
        val owner = state(); val request = owner.capture()
        val generation = owner.nextLoginGeneration(); pending.clear()
        val value = when (methodId) {
            "qr" -> owner.api.generateQrCode().let { qr ->
                owner.check(request); currentCoroutineContext().ensureActive()
                create(BiliPendingLogin(request, generation, "qr", key = qr.qrcodeKey), "请使用哔哩哔哩扫描二维码", qr.url)
            }
            "sms" -> {
                val country = input["countryCode"].orEmpty().ifBlank { "86" }
                val phone = requireNotNull(input["phone"]?.takeIf(String::isNotBlank)) { "请输入手机号" }
                val base = BiliPendingLogin(request, generation, "sms", country = country, phone = phone)
                smsResult(base, owner.api.sendSmsCode(country, phone))
            }
            else -> throw IllegalArgumentException("不支持此登录方式")
        }
        owner.check(request); check(owner.authGeneration.get() == generation) { "登录请求已取消" }
        value
    }
    override suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult = withContext(Dispatchers.IO) {
        val owner = state(); val login = requireNotNull(pending[challenge.id]) { "登录挑战已失效，请重新开始" }
        owner.check(login.request); check(owner.authGeneration.get() == login.generation) { "登录请求已取消" }
        val result = when (login.kind) {
            "qr" -> {
                val poll = owner.api.pollQrCode(login.key)
                when (poll.code) {
                    0 -> {
                        val session = owner.api.getUserInfo(poll.cookie.orEmpty()) ?: error("扫码成功但账号校验失败，请重试")
                        currentCoroutineContext().ensureActive(); owner.saveSession(login.request, session, login.generation); pending.clear()
                        SourceAuthResult(SourceAuthProgress.SUCCEEDED, "登录成功", account = session.summary())
                    }
                    86090 -> SourceAuthResult(SourceAuthProgress.CONFIRMATION_REQUIRED, poll.message, challenge)
                    86038 -> { pending.remove(challenge.id); SourceAuthResult(SourceAuthProgress.EXPIRED, "二维码已过期，请重新生成") }
                    else -> SourceAuthResult(SourceAuthProgress.WAITING, poll.message.ifBlank { "等待扫码" }, challenge)
                }
            }
            "verification" -> {
                val previous = requireNotNull(login.verification)
                val proof = BiliGeetestResult(input["geetest_challenge"].orEmpty(), input["geetest_validate"].orEmpty(),
                    input["geetest_seccode"].orEmpty(), previous.token)
                require(proof.isComplete()) { "安全验证结果不完整，请重试" }
                val next = smsResult(login, owner.api.sendSmsCode(login.country, login.phone, proof))
                pending.remove(challenge.id)
                SourceAuthResult(if (next.kind == "verification") SourceAuthProgress.VERIFICATION_REQUIRED else SourceAuthProgress.WAITING, next.message, next)
            }
            "sms" -> {
                val code = requireNotNull(input["code"]?.takeIf(String::isNotBlank)) { "请输入短信验证码" }
                val session = owner.api.loginBySms(login.country, login.phone, code, login.key)
                currentCoroutineContext().ensureActive(); owner.saveSession(login.request, session, login.generation); pending.clear()
                SourceAuthResult(SourceAuthProgress.SUCCEEDED, "登录成功", account = session.summary())
            }
            else -> error("未知登录挑战")
        }
        if (result.progress != SourceAuthProgress.SUCCEEDED) {
            owner.check(login.request); check(owner.authGeneration.get() == login.generation) { "登录请求已取消" }
        }
        currentCoroutineContext().ensureActive()
        result
    }
    private fun smsResult(base: BiliPendingLogin, result: BiliSmsSendResult): SourceAuthChallenge = when (result) {
        is BiliSmsSendResult.Success -> create(base.copy(kind = "sms", key = result.captchaKey, verification = null), "验证码已发送")
        is BiliSmsSendResult.NeedGeetest -> create(base.copy(kind = "verification", verification = result.challenge), "请完成安全验证")
        is BiliSmsSendResult.Failed -> throw PlatformApiException(result.message, result.code)
    }
    private fun create(value: BiliPendingLogin, message: String, url: String? = null): SourceAuthChallenge {
        val id = UUID.randomUUID().toString(); pending[id] = value
        val proof = value.verification
        val payload = if (proof == null) SourcePayload() else SourcePayload("bilibili.auth.verification", 1,
            JSONObject().put("gt", proof.gt).put("challenge", proof.challenge).put("token", proof.token).toString())
        return SourceAuthChallenge(id, value.kind, message, displayUrl = url, payload = payload)
    }
    override suspend fun refreshAccount(): SourceAccountState {
        val owner = state(); val request = owner.capture()
        if (request.session == null) return owner.account()
        val session = withContext(Dispatchers.IO) { owner.api.getUserInfo(request.session.cookie) }
        currentCoroutineContext().ensureActive(); owner.check(request)
        // 网络异常直接抛出并保留完整会话；只有平台明确返回失效才标记过期。
        if (session == null) owner.expired = true else owner.saveSession(request, session)
        return owner.account()
    }
    override suspend fun logout() { cancel(); state().logout() }
    override fun exportSession(): SourceSessionSnapshot? {
        val context = state().environment.context
        if (state().sessions.read() == null) return null
        val fields = buildMap { listOf("musicone_bilibili_session", "musicone_bili_device").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) -> if (value != null) put("$name/$key", value.toString()) }
        } }
        return SourceSessionSnapshot("bilibili.full-session", 1, fields)
    }
    override fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt {
        require(session.sourceId == BILI_SOURCE_ID)
        val owner = state()
        val markers = owner.environment.context.getSharedPreferences("source_migrations", Context.MODE_PRIVATE)
        if (markers.getBoolean(session.migrationId, false)) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.ALREADY_IMPORTED)
        if (owner.sessions.read() != null) return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.EXISTING_SESSION_PRESERVED)
        val values = session.preferenceNamespaces["musicone_bilibili_session"].orEmpty()
        require(!values["cookie"].isNullOrBlank() && !values["mid"].isNullOrBlank()) { "旧会话缺少完整凭证" }
        listOf("musicone_bili_device", "musicone_bilibili_session").forEach { name ->
            val editor = owner.environment.context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            session.preferenceNamespaces[name].orEmpty().forEach { (key, value) -> editor.putString(key, value) }
            check(editor.commit()) { "旧会话写入失败，已保留迁移输入" }
        }
        check(markers.edit().putBoolean(session.migrationId, true).commit())
        owner.revision.incrementAndGet()
        return SourceMigrationReceipt(session.migrationId, SourceMigrationStatus.IMPORTED)
    }
}
