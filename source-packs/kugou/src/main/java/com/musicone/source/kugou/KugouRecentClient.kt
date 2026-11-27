package com.musicone.source.kugou

import com.musicone.source.sdk.SourceRecentSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 拉取酷狗账号的云端最近播放记录（bp 为分页游标）。 */
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
                // pc 是播放次数不是条数；同一首歌出现多条时只保留播放时间（ot）最新的一条
                if (previous == null || entry.timeSec > previous.timeSec) unique[entry.key] = entry
            }
            // 是否还有下一页看 has_more==1，最后一页有时仍会带 bp 字段，不能光看 bp 非空就继续翻页
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
        // 全部拉完去重后再统计实际单曲总数
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
    // 如果歌曲本身缺 ID，用云端的 mxid 补上 mixsongid，但不改动原来的 hash 主 ID
    if (mxid != null && normalized.contentText("MixSongID", "mixsongid", "album_audio_id", "audio_id", "audioid").isBlank()) {
        normalized.put("mixsongid", mxid)
    }
    val track = normalized.kugouPublicCollectionTrack(vip)
    val id = mxid ?: track.catalogId.takeIf { it.toLongOrNull()?.let { value -> value > 0 } == true }
    val key = id?.let { "mxid:$it" } ?: "hash:${track.remoteId().lowercase()}"
    val time = contentText("ot").toLongOrNull()?.coerceAtLeast(0) ?: 0
    return KugouRecentEntry(key, time, track)
}
