package com.musicone.source.kugou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/** 时长与播放事件各自确认，任何一步失败都不能冒充另一项已成功。 */
internal interface KugouListeningService {
    suspend fun baseline(cookie: String, check: () -> Unit): Long
    suspend fun sync(cookie: String, baselineSec: Long, addedSec: Long, check: () -> Unit): Long
    suspend fun playback(cookie: String, songId: Long, started: Boolean, playedMs: Long,
        completed: Boolean, occurredAtMs: Long, check: () -> Unit)
}

internal data class KugouGradeRequest(val url: String, val body: String, val headers: Map<String, String>)

internal class KugouListeningClient(
    private val cscc: KugouListeningCscc,
    private val now: () -> Long = System::currentTimeMillis,
    private val sendGrade: suspend (KugouGradeRequest, String) -> JSONObject = { request, cookie ->
        runInterruptible(Dispatchers.IO) {
            val result = PlatformHttp.postJson(request.url, request.body, cookie, request.headers)
            JSONObject(result.text)
        }
    },
) : KugouListeningService {
    override suspend fun baseline(cookie: String, check: () -> Unit): Long = grade(cookie, null, null, check)
    override suspend fun sync(cookie: String, baselineSec: Long, addedSec: Long, check: () -> Unit): Long =
        grade(cookie, baselineSec, addedSec, check)

    private suspend fun grade(cookie: String, baseline: Long?, added: Long?, check: () -> Unit): Long {
        check()
        val response = sendGrade(kugouGradeRequest(cookie, now(), baseline, added), cookie)
        check()
        require(response.optInt("status", 0) == 1 &&
            response.optInt("error_code", response.optInt("errcode", 0)) == 0) { "酷狗未确认听歌时长请求成功" }
        val data = response.optJSONObject("data")
            ?: response.optString("data").takeIf { it.startsWith("{") }?.let(::JSONObject)
        return requireNotNull(data?.optLong("d_sec", -1)?.takeIf { it >= 0 }) { "酷狗未返回真实累计听歌时长" }
    }

    override suspend fun playback(cookie: String, songId: Long, started: Boolean, playedMs: Long,
        completed: Boolean, occurredAtMs: Long, check: () -> Unit) =
        cscc.report(cookie, songId, started, playedMs, completed, occurredAtMs, check)
}

/** 沿用本音源的 3116 身份及 RSA，查询基线后只提交本会话真实新增秒数。 */
internal fun kugouGradeRequest(cookie: String, nowMs: Long, baselineSec: Long?, addedSec: Long?): KugouGradeRequest {
    val session = cookie.requiredKugouSession()
    require((baselineSec == null) == (addedSec == null)) { "时长基线与增量必须同时提供" }
    require(baselineSec == null || baselineSec >= 0)
    require(addedSec == null || addedSec > 0)
    val time = nowMs / 1_000
    val values = cookie.cookieValues()
    val body = JSONObject().put("mid", session.mid).put("type", 1)
        .put("uuid", values["KUGOU_API_GUID"].orEmpty().ifBlank { "-" }).put("userid", session.userId.toLong())
    val proof = if (baselineSec == null) JSONObject().put("clienttime", time).put("userid", session.userId.toLong())
        else {
            body.put("d_sec", baselineSec).put("diff_sec", addedSec).put("y_type", 0).put("m_type", 0)
            // 沿用同一 3116 公开协议的默认字段值，不从音频可播放权益推断这两个字段。
            JSONObject().put("token", session.token).put("md5", KugouProtocol.md5("${baselineSec}${addedSec}00"))
        }
    body.put("p", KugouLoginCrypto.rawRsaEncrypt(proof.toString()))
        .put("appid", KugouProtocol.appId).put("clientver", KugouProtocol.clientVersion)
        .put("clienttime", time).put("key", KugouProtocol.loginKey(time))
    return KugouGradeRequest(kugouQueryUrl("https://userinfo.user.kugou.com/v2/get_grade_info",
        mapOf("dfid" to values["dfid"].orEmpty().ifBlank { "-" })), body.toString(), mapOf(
        "Content-Type" to "text/plain; charset=ISO-8859-1",
        "User-Agent" to "Android${android.os.Build.VERSION.RELEASE}-1070-${KugouProtocol.clientVersion}-201-0-get_user_grade_info",
        "KG-Rec" to "1", "KG-RC" to "1"))
}
