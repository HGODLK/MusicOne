package com.musicone.source.kugou

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 只使用当前账号接口确认会员；本地历史布尔值和登录成功都不是会员凭证。 */
internal class KugouMembershipClient(
    private val load: (String) -> JSONObject = ::loadKugouMembership,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Snapshot(val cookie: String, val checkedAt: Long, val access: Boolean?)
    @Volatile private var snapshot: Snapshot? = null
    private val publication = Any()
    private val generation = AtomicLong()
    val revision = AtomicLong()

    fun cached(cookie: String): Boolean? = snapshot?.takeIf {
        it.cookie == cookie && now() - it.checkedAt in 0 until KUGOU_MEMBERSHIP_REFRESH_MS
    }?.access

    fun hasVipAccess(cookie: String): Boolean = detectMembership(cookie) == true

    @Synchronized fun detectMembership(cookie: String): Boolean? {
        val previous = snapshot?.takeIf { it.cookie == cookie }
        val age = previous?.let { now() - it.checkedAt }
        val refreshAfter = if (previous?.access == null) KUGOU_MEMBERSHIP_RETRY_MS else KUGOU_MEMBERSHIP_REFRESH_MS
        if (age != null && age in 0 until refreshAfter) return previous?.access
        val captured = generation.get()
        val access = try { parseKugouMembership(load(cookie)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { throw interrupted }
        catch (_: Exception) { null }
        return synchronized(publication) {
            if (generation.get() == captured) {
                if (previous?.access != access || snapshot?.cookie != cookie) revision.incrementAndGet()
                snapshot = Snapshot(cookie, now(), access)
                access
            } else null
        }
    }

    fun clear() { synchronized(publication) { generation.incrementAndGet(); snapshot = null; revision.incrementAndGet() } }
}

/** 普通歌曲依据音乐包或畅听权益；通用会员身份和超级会员位不能代替曲目的听歌授权。 */
internal fun parseKugouMembership(response: JSONObject): Boolean? {
    if (response.optInt("status", 0) != 1 || response.optInt("error_code", 0) != 0) return null
    val data = response.optJSONObject("data") ?: return null
    val user = data.opt("user_type")?.toString()?.toIntOrNull()
    // 官方普通音乐权限同时接受音乐包和此畅听位，不使用相邻的会员展示位。
    if (user != null && user >= 0 && user and 256 != 0) return true
    return when (data.opt("m_type")?.toString()?.toIntOrNull()) {
        0 -> false
        in 1..4 -> true
        else -> null
    }
}

internal fun kugouMembershipBody(session: KugouSession, seconds: Long): JSONObject = JSONObject()
    .put("visit_time", seconds).put("usertype", 1)
    .put("p", KugouLoginCrypto.rawRsaEncrypt(JSONObject().put("token", session.token).put("clienttime", seconds).toString()))
    .put("userid", session.userId.toLong())

private fun loadKugouMembership(cookie: String): JSONObject {
    val session = cookie.requiredKugouSession()
    val seconds = System.currentTimeMillis() / 1_000
    val params = linkedMapOf("dfid" to session.dfid, "mid" to session.mid, "uuid" to "-",
        "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
        "clienttime" to seconds.toString(), "token" to session.token, "userid" to session.userId, "plat" to "1")
    val body = kugouMembershipBody(session, seconds).toString()
    return JSONObject(PlatformHttp.postJson(
        KugouProtocol.signedAndroidUrl("https://gateway.kugou.com/v3/get_my_info", params, body),
        body, cookie, kugouAndroidHeaders(params, "usercenter.kugou.com"),
        connectTimeoutMs = 5_000, readTimeoutMs = 8_000,
    ).text)
}

private const val KUGOU_MEMBERSHIP_REFRESH_MS = 5 * 60_000L
private const val KUGOU_MEMBERSHIP_RETRY_MS = 30_000L
