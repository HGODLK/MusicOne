package com.musicone.demo

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** QQ 账号专属首页数据，与猜你喜欢电台协议保持独立。 */
internal class QqPersonalizedClient {
    fun dailyTracks(
        credential: String,
        deviceId: String,
        hasVipAccess: Boolean,
    ): List<MusicTrack> {
        val values = credential.cookieValues()
        val accountId = qqCredentialAccountId(credential)
        val musicKey = qqCredentialMusicKey(credential)
        if (accountId.isBlank() || musicKey.isBlank()) {
            throw PlatformApiException("登录 QQ 音乐后才能获取每日推荐", 301)
        }
        val hostUin = values["encryptUin"].orEmpty().ifBlank {
            values["encrypt_uin"].orEmpty().ifBlank { accountId }
        }
        val request = JSONObject()
            .put("module", "music.srfDissInfo.DissInfo")
            .put("method", "CgiGetDiss")
            .put("param", JSONObject()
                .put("new_format", 1)
                .put("enc_host_uin", hostUin)
                .put("dirid", DAILY_DIRECTORY_ID)
                .put("onlysonglist", 1)
                .put("optype", 0)
                .put("orderlist", 0)
                .put("guid", qqStableGuid(deviceId))
                .put("is_mobile", 1)
                .put("local_time", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
                .put("update_rtime", 0))
        val tracks = post(
            JSONObject().put("comm", qqPlaybackComm(credential)).put("req_0", request),
            credential,
        ).parseQqDailyTracks(hasVipAccess).take(DAILY_TRACK_LIMIT)
        if (tracks.isEmpty()) throw PlatformApiException("QQ 音乐没有返回今日歌单")
        return QqTrackAccessResolver().resolve(tracks, credential)
    }

    fun recommendedPlaylists(credential: String, page: Int = 0): List<MusicPlaylist> {
        if (qqCredentialAccountId(credential).isBlank() || qqCredentialMusicKey(credential).isBlank()) {
            throw PlatformApiException("登录 QQ 音乐后才能获取专属推荐", 301)
        }
        val request = JSONObject()
            .put("module", "music.playlist.PlaylistSquare")
            .put("method", "GetRecommendFeed")
            .put("param", JSONObject().put("From", qqRecommendationOffset(page)).put("Size", RECOMMENDATION_LIMIT))
        val playlists = post(
            JSONObject().put("comm", qqPlaybackComm(credential)).put("req_0", request),
            credential,
        ).parseQqPersonalizedPlaylists().take(RECOMMENDATION_LIMIT)
        if (playlists.isEmpty()) throw PlatformApiException("QQ 音乐没有返回专属推荐歌单")
        return playlists
    }

    private fun post(body: JSONObject, credential: String): JSONObject {
        val response = PlatformHttp.postJson(
            MUSIC_U_API,
            body.toString(),
            credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 14090008(android 14)"),
        )
        val json = JSONObject(response.text)
        if (json.optInt("code", 0) != 0) {
            throw PlatformApiException(json.optString("message").ifBlank { "QQ 音乐请求失败" })
        }
        val subRequest = json.optJSONObject("req_0")
        if (subRequest != null && subRequest.optInt("code", 0) != 0) {
            throw PlatformApiException(
                subRequest.optString("message").ifBlank {
                    subRequest.optJSONObject("data")?.optString("msg").orEmpty().ifBlank { "QQ 音乐请求失败" }
                },
                subRequest.optInt("code"),
            )
        }
        return json
    }

    private companion object {
        const val MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val DAILY_DIRECTORY_ID = 202
        const val DAILY_TRACK_LIMIT = 30
        const val RECOMMENDATION_LIMIT = 8
    }
}

internal fun qqStableGuid(deviceId: String): String {
    var hash = 1_125_899_906_842_597L
    deviceId.ifBlank { "musiconeandroidclient" }.forEach { value ->
        hash = (hash xor value.code.toLong()) * 1_099_511_628_211L
    }
    val positive = hash and Long.MAX_VALUE
    return (positive % 9_000_000_000L + 1_000_000_000L).toString()
}

internal fun qqRecommendationOffset(page: Int): Int = page.coerceAtLeast(0) * 8
