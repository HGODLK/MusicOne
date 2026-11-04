package com.musicone.source.qq.internal

import android.content.Context
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.Calendar

/** 统计设备会话与音乐登录分离，仅在实际结算时获取或按自然日续期。 */
internal class QqListenDurationClient(context: Context) {
    private val preferences = context.getSharedPreferences("qq_listen_duration_device", Context.MODE_PRIVATE)
    private val device get() = QqDurationDevice(Build.VERSION.RELEASE.orEmpty(), Build.MODEL.orEmpty())

    suspend fun prepare(session: PlatformSession, isCurrent: () -> Boolean): Pair<String, String> {
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "QQ账号已变化，时长结算已取消" }
        val deviceId = session.deviceId
        val today = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }
        val sameDevice = preferences.getString("device", "") == deviceId
        val uid = if (sameDevice) preferences.getString("uid", "").orEmpty() else ""
        val sid = if (sameDevice) preferences.getString("sid", "").orEmpty() else ""
        if (uid.isNotBlank() && sid.isNotBlank() && preferences.getString("day", "") == today) return uid to sid
        val body = qqDurationSessionRequest(deviceId, device, uid)
        val response = JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(),
            headers = mapOf("User-Agent" to "QQMusic 14090008(android ${device.os})")).text)
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "QQ账号已变化，时长结算已取消" }
        val ready = qqDurationSessionResponse(response)
        check(preferences.edit().putString("device", deviceId).putString("uid", ready.first)
            .putString("sid", ready.second).putString("day", today).commit()) { "QQ统计设备会话保存失败" }
        return ready
    }

    fun report(session: PlatformSession, track: MusicTrack, vkey: String, durationMs: Long, occurredAtMs: Long,
        anonymous: Pair<String, String>) {
        val xml = qqListenDurationXml(session, device, anonymous.first, anonymous.second, track, vkey, durationMs, occurredAtMs)
        requireQqListenDurationSuccess(PlatformHttp.postBytes(
            "https://stat6.y.qq.com/android/fcgi-bin/imusic_tj", qqGzipXml(xml), session.credential,
            mapOf("Content-Type" to "application/octet-stream", "Content-Encoding" to "gzip",
                "User-Agent" to "QQMusic 14090008(android ${device.os})", "Accept" to "*/*")))
    }
}

internal fun qqDurationSessionRequest(deviceId: String, device: QqDurationDevice, previousUid: String): JSONObject =
    JSONObject().put("comm", qqAndroidLoginComm(deviceId, false)
        .put("os_ver", device.os).put("phonetype", device.model))
        .put("req_0", JSONObject().put("module", "music.getSession.session").put("method", "GetSession")
            .put("param", JSONObject().put("uid", previousUid).put("vkey", 0)
                .put("caller", if (previousUid.isBlank()) 2 else 1)))

internal fun qqDurationSessionResponse(response: JSONObject): Pair<String, String> {
    val result = response.optJSONObject("req_0") ?: throw PlatformApiException("QQ统计设备会话响应缺失")
    if (response.optInt("code", -1) != 0 || result.optInt("code", -1) != 0)
        throw PlatformApiException("QQ统计设备会话获取失败")
    val data = result.optJSONObject("data")?.optJSONObject("session")
        ?: throw PlatformApiException("QQ统计设备会话缺失")
    val uid = data.optString("uid").takeUnless { it == "null" }.orEmpty()
    val sid = data.optString("sid").takeUnless { it == "null" }.orEmpty()
    if (uid.isBlank() || sid.isBlank()) throw PlatformApiException("QQ统计设备会话不完整")
    return uid to sid
}
