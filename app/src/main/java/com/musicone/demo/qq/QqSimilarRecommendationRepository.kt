package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 只负责按当前 QQ 歌曲读取相似推荐，避免和“猜你喜欢”连续电台混用状态。 */
internal class QqSimilarRecommendationRepository(
    context: Context,
    private val artworkAliases: QqArtworkAliasStore = QqArtworkAliasStore(context),
) {
    private val preferences = PlatformPreferences(context)
    private val api = QqApiClient()

    suspend fun load(
        baseTrack: MusicTrack,
        amount: Int = QQ_SIMILAR_RECOMMENDATION_SIZE,
    ): QqSimilarRecommendation = withContext(Dispatchers.IO) {
        val songId = baseTrack.catalogId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw PlatformApiException("当前歌曲缺少 QQ 音乐数字 ID")
        val session = preferences.readSession(MusicSource.QQ)
        if (qqCredentialAccountId(session.credential).isBlank() ||
            qqCredentialMusicKey(session.credential).isBlank()
        ) {
            throw PlatformApiException("登录 QQ 音乐后才能获取相似推荐", 301)
        }
        val body = JSONObject()
            .put("comm", qqPlaybackComm(session.credential))
            .put("req_0", qqSimilarRecommendationRequest(songId, amount = amount))
        val response = PlatformHttp.postJson(
            QQ_SIMILAR_MUSIC_U_API,
            body.toString(),
            session.credential,
            mapOf(
                "Referer" to "https://y.qq.com/",
                "User-Agent" to "QQMusic 14090008(android 14)",
            ),
        ).let { JSONObject(it.text) }
        requireQqSimilarSuccess(response)
        val recommendation = response.parseQqSimilarRecommendation(
            baseTrack = baseTrack,
            hasVipAccess = session.account?.hasVipAccess == true,
        )
        if (recommendation.songs.isEmpty()) throw PlatformApiException("QQ 音乐没有返回相似推荐")
        val ready = coroutineScope {
            // 权限查询与封面补查彼此独立，同时准备，仍等完整数据就绪后按原动效发布。
            val permissionRequest = async(Dispatchers.IO) {
                QqTrackAccessResolver().resolve(recommendation.songs.map { it.track }, session.credential)
                    .associateBy(MusicTrack::id)
            }
            val artworkLimiter = Semaphore(2)
            val resolvedSongs = recommendation.songs.map { song ->
                async(Dispatchers.IO) {
                    val aliased = artworkAliases.apply(song.track)
                    val resolved = if (aliased.artworkUrl.isNullOrBlank()) {
                        artworkLimiter.withPermit {
                            runCatching {
                                api.resolveTrackArtwork(
                                    aliased,
                                    session.credential,
                                    session.account?.hasVipAccess == true,
                                )
                            }.getOrDefault(aliased)
                        }
                    } else aliased
                    song.copy(track = artworkAliases.remember(song.track, resolved))
                }
            }.awaitAll()
            val permissions = permissionRequest.await()
            resolvedSongs.map { song ->
                val authorized = permissions[song.track.id] ?: song.track
                song.copy(track = authorized.copy(artworkUrl = song.track.artworkUrl, album = song.track.album))
            }
        }
        recommendation.copy(
            baseTrack = artworkAliases.apply(recommendation.baseTrack),
            songs = ready,
        )
    }
}

private fun requireQqSimilarSuccess(response: JSONObject) {
    if (response.optInt("code", 0) != 0) {
        throw PlatformApiException(response.optString("message").ifBlank { "QQ 音乐相似推荐请求失败" })
    }
    val request = response.optJSONObject("req_0") ?: return
    if (request.optInt("code", 0) != 0) {
        throw PlatformApiException(
            request.optString("message").ifBlank {
                request.optJSONObject("data")?.optString("msg").orEmpty()
                    .ifBlank { "QQ 音乐相似推荐请求失败" }
            },
            request.optInt("code"),
        )
    }
}

private const val QQ_SIMILAR_MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
