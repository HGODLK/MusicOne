package com.musicone.source.kugou

import com.musicone.source.sdk.SourceRecentSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 只读账号云端单曲历史；bp 是平台游标，不创建歌单，也不提交播放记录。 */
internal class KugouRecentClient(private val client: KugouContentClient = KugouContentClient()) {
    suspend fun snapshot(cookie: String, vip: Boolean, checkOwner: () -> Unit = {}): SourceRecentSnapshot {
        val session = cookie.requiredKugouSession()
        val unique = linkedMapOf<String, KugouRecentEntry>()
        val cursors = mutableSetOf<String>()
        val pages = mutableSetOf<List<Pair<String, Long>>>()
        var bp: String? = null
        suspend fun check() { currentCoroutineContext().ensureActive(); checkOwner() }
        do {
            check()
            val body = JSONObject().put("userid", session.userId).put("token", session.token)
                .put("source_classify", "app").put("to_subdivide_sr", 1)
                .apply { bp?.let { put("bp", it) } }
            val response = client.load(KugouContentRequest("/playhistory/v1/get_songs", router = null, body = body), cookie)
            check()
            if (response.optInt("status", -1) != 1) throw PlatformApiException("酷狗未确认云端播放历史读取成功")
            val data = response.optJSONObject("data") ?: throw PlatformApiException("酷狗未返回云端播放历史")
            val raw = data.optJSONArray("songs") ?: throw PlatformApiException("酷狗未返回云端播放历史单曲列表")
            val entries = (0 until raw.length()).map { index ->
                raw.optJSONObject(index)?.kugouRecentEntry(vip)
                    ?: throw PlatformApiException("酷狗云端播放历史记录格式错误")
            }
            if (entries.isNotEmpty() && !pages.add(entries.map { it.key to it.timeSec })) {
                throw PlatformApiException("酷狗云端播放历史重复返回同一页，请刷新后重试")
            }
            entries.forEach { entry ->
                val previous = unique[entry.key]
                // pc 是累计次数，不是独立历史条数；同一云端曲目只保留最新 ot 的资料。
                if (previous == null || entry.timeSec > previous.timeSec) unique[entry.key] = entry
            }
            // 官方以 has_more==1 决定继续，结束页仍可能保留 bp，不能把残留游标当成下一页。
            val more = if (data.has("has_more") && !data.isNull("has_more")) {
                data.opt("has_more").toString().toIntOrNull()?.let { it == 1 }
                    ?: throw PlatformApiException("酷狗云端播放历史分页标记格式错误")
            } else null
            val next = if (more == false) null else {
                val cursor = data.opt("bp").takeUnless { it == null || it == JSONObject.NULL }
                    ?: response.opt("bp").takeUnless { it == null || it == JSONObject.NULL }
                if (cursor != null && cursor !is String && cursor !is Number) throw PlatformApiException("酷狗云端播放历史游标格式错误")
                cursor?.toString()?.takeUnless { it.isBlank() || it == "0" }
            }
            if (more == true && next == null) throw PlatformApiException("酷狗云端播放历史缺少下一页游标，请刷新后重试")
            if (next != null && (entries.isEmpty() || !cursors.add(next))) {
                throw PlatformApiException("酷狗云端播放历史分页没有前进，请刷新后重试")
            }
            bp = next
        } while (bp != null)
        check()
        // 完整读取之后才计算实际单曲总数，不把首批长度、pc 或其他设备截图数字当成 total。
        val tracks = unique.values.sortedByDescending { it.timeSec }
            .map { KugouSourceMapping.track(it.track, session.userId) }
        return SourceRecentSnapshot(tracks = tracks, totalTracks = tracks.size)
    }
}

private data class KugouRecentEntry(val key: String, val timeSec: Long, val track: MusicTrack)

private fun JSONObject.kugouRecentEntry(vip: Boolean): KugouRecentEntry {
    val info = optJSONObject("info") ?: throw PlatformApiException("酷狗云端播放历史缺少歌曲资料")
    val normalized = JSONObject(info.toString())
    val mxid = contentText("mxid").takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }
    // mxid 是云端曲目编号，只在歌曲本身缺少编号时补 mixsongid，绝不替代原 hash 身份。
    if (mxid != null && normalized.contentText("MixSongID", "mixsongid", "album_audio_id", "audio_id", "audioid").isBlank()) {
        normalized.put("mixsongid", mxid)
    }
    val track = normalized.kugouPublicCollectionTrack(vip)
    val id = mxid ?: track.catalogId.takeIf { it.toLongOrNull()?.let { value -> value > 0 } == true }
    val key = id?.let { "mxid:$it" } ?: "hash:${track.remoteId().lowercase()}"
    val time = contentText("ot").toLongOrNull()?.coerceAtLeast(0) ?: 0
    return KugouRecentEntry(key, time, track)
}
