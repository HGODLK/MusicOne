package com.musicone.demo

import org.json.JSONObject

/** 所有列表共用账号权益和歌曲详情，缺失字段才补查，失败保留原限制。 */
internal class QqTrackAccessResolver(
    private val membership: (String) -> QqMembership? = QqEntitlements::membership,
    private val details: (List<MusicTrack>, String) -> JSONObject = ::requestQqTrackAccess,
) {
    fun resolve(tracks: List<MusicTrack>, credential: String, fresh: Boolean = true): List<MusicTrack> {
        if (tracks.isEmpty()) return tracks
        val accountId = qqCredentialAccountId(credential)
        val member = membership(credential)
        val now = System.currentTimeMillis()
        val prepared = tracks.map { track ->
            if (track.source != MusicSource.QQ) return@map track
            val raw = track.qqAccess ?: QqTrackAccessInfo(trial = track.trialAvailable)
            val info = if (fresh && raw.payStatus != null && raw.permissionAccountId.isBlank()) {
                raw.copy(permissionAccountId = accountId, checkedAtMs = now)
            } else raw
            track.copy(qqAccess = info).withQqAccess(accountId, member?.vip == true, member?.superVip == true)
        }
        val resolved = mutableMapOf<String, QqTrackAccessInfo>()
        val missing = prepared.filter { track ->
            if (track.source != MusicSource.QQ) return@filter false
            val cached = QqEntitlements.cached(credential, track.id)
            if (cached != null && !(fresh && track.qqAccess?.complete == true &&
                    track.qqAccess.kind != cached.kind) && !(fresh && track.qqAccess?.payStatus != null &&
                    track.qqAccess.permissionAccountId == accountId)) {
                resolved[track.id] = cached
                return@filter false
            }
            val info = track.qqAccess ?: return@filter true
            !info.complete || (accountId.isNotBlank() && track.accessBadge != null &&
                (info.payStatus == null || info.permissionAccountId != accountId ||
                    now - info.checkedAtMs !in 0 until QQ_ENTITLEMENT_TTL_MS))
        }.distinctBy { it.id }
        for (batch in missing.chunked(20)) {
            try {
                val response = details(batch, credential)
                batch.forEachIndexed { index, track ->
                    val request = response.optJSONObject("access_$index") ?: return@forEachIndexed
                    if (request.optInt("code", -1) != 0) return@forEachIndexed
                    val detail = request.optJSONObject("data")?.optJSONObject("track_info") ?: return@forEachIndexed
                    if (!qqAccessDetailMatches(track, detail)) return@forEachIndexed
                    val info = detail.qqTrackAccessInfo(track.qqAccess?.trial ?: track.trialAvailable)
                        .copy(permissionAccountId = accountId, checkedAtMs = now)
                    if (!info.complete) return@forEachIndexed
                    resolved[track.id] = info
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException || error is InterruptedException ||
                Thread.currentThread().isInterrupted || error.stopsPlaybackFallback()) throw error
            }
        }
        return prepared.map { track ->
            val info = resolved[track.id] ?: track.qqAccess ?: return@map track
            if (info.complete && info.checkedAtMs > 0L) QqEntitlements.remember(credential, track.id, info)
            track.copy(qqAccess = info).withQqAccess(accountId, member?.vip == true, member?.superVip == true)
        }
    }
}

private fun requestQqTrackAccess(tracks: List<MusicTrack>, credential: String): JSONObject {
    val response = PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg",
        qqTrackAccessRequest(tracks, credential).toString(), credential,
        mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 20050009(android 14)"),
        connectTimeoutMs = 5_000, readTimeoutMs = 8_000)
    val result = JSONObject(response.text)
    if (result.optInt("code", -1) != 0) {
        throw PlatformApiException("QQ 音乐歌曲权益查询失败", result.optInt("code", -1))
    }
    // 单首详情失败不能丢掉同批其他歌曲的有效权限，也不在列表角标查询中弹出播放验证。
    return result
}

internal fun qqTrackAccessRequest(tracks: List<MusicTrack>, credential: String): JSONObject =
    JSONObject().put("comm", qqPlaybackComm(credential).put("uin", qqCredentialAccountId(credential))
        .put("authst", qqCredentialMusicKey(credential))).apply {
        tracks.forEachIndexed { index, track ->
            put("access_$index", JSONObject().put("module", "music.pf_song_detail_svr")
                .put("method", "get_song_detail_yqq").put("param", JSONObject()
                    .put("song_mid", track.qqPlaybackMid()).put("song_type", track.providerType)
                    .put("song_id", track.catalogId.toLongOrNull() ?: 0L)))
        }
    }

internal fun qqAccessDetailMatches(track: MusicTrack, detail: JSONObject): Boolean {
    if (detail.has("type") && detail.optInt("type") != track.providerType) return false
    val mid = detail.optString("mid").ifBlank { detail.optString("songmid") }
    val id = detail.optString("id").ifBlank { detail.optString("songid") }
    if (track.qqPlaybackMid().isNotBlank()) return mid == track.qqPlaybackMid()
    return track.catalogId.isNotBlank() && id == track.catalogId
}
