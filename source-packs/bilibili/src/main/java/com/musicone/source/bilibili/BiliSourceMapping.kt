package com.musicone.source.bilibili

import com.musicone.source.sdk.*
import org.json.JSONObject

internal object BiliSourceMapping {
    private val bvidPattern = Regex("(?i)(?<![a-z0-9])(BV1[a-zA-Z0-9]{9})(?![a-z0-9])")
    fun expandLink(input: String): String {
        val link = Regex("https?://b23\\.tv/[^\\s]+|b23\\.tv/[^\\s]+").find(input)?.value ?: return input
        val response = PlatformHttp.get(if (link.startsWith("http")) link else "https://$link", followRedirects = false)
        return response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull() ?: input
    }
    fun bvid(input: String): String? = bvidPattern.find(input)?.groupValues?.get(1)?.let { "BV" + it.substring(2) }
    fun selectedPage(input: String): Int? = Regex("(?i)[?&]p=(\\d+)").find(input)?.groupValues?.get(1)?.toIntOrNull()
    fun content(video: BiliVideoItem, account: String?, preferredPage: Int? = null): SourceContent {
        val json = JSONObject().put("bvid", video.bvid).put("aid", video.aid).put("cid", video.cid)
            .put("invalid", video.isInvalid).put("pages", video.pageCount).put("selectedPage", preferredPage ?: JSONObject.NULL)
        return SourceContent(TrackIdentity(BILI_SOURCE_ID, account, video.bvid.ifBlank { "av${video.aid}" }), video.title,
            author = SourceEntityRef("", video.upName, "creator"), artwork = SourceArtwork(video.cover.takeIf(String::isNotBlank)),
            durationMs = video.durationSec * 1000L,
            access = TrackAccessInfo(playable = !video.isInvalid, reason = if (video.isInvalid) "视频已失效或已被删除" else null),
            payload = SourcePayload("bilibili.content", 1, json.toString()),
            playableTrack = track(video, null, account, false))
    }
    fun video(content: SourceContent): BiliVideoItem {
        require(content.identity.sourceId == BILI_SOURCE_ID)
        val json = payload(content.payload)
        return BiliVideoItem(json.optLong("aid"), json.optString("bvid").ifBlank { bvid(content.identity.remoteId).orEmpty() },
            json.optLong("cid"), content.title, content.artwork.url.orEmpty(), content.author?.name.orEmpty(),
            content.durationMs / 1000, !content.access.playable || json.optBoolean("invalid"), json.optInt("pages", 0))
    }
    fun track(video: BiliVideoItem, part: BiliPagePart?, account: String?, multiParts: Boolean): SourceTrackDetails {
        val cid = part?.cid ?: video.cid
        val id = "bilibili-${video.bvid.ifBlank { "av${video.aid}" }}_$cid"
        // 多 P 标题为“分P名 视频标题”；分 P 名为空时用 P 序号，与视频标题相同时只显示一次。
        val partTitle = part?.part.orEmpty().trim().ifBlank { part?.let { "P${it.page}" }.orEmpty() }
        val title = if (multiParts && partTitle.isNotBlank() && partTitle != video.title) "$partTitle ${video.title}".trim()
            else video.title.ifBlank { partTitle.ifBlank { "B站音频" } }
        val valid = !video.isInvalid && video.bvid.isNotBlank() && (part == null || cid > 0)
        val data = mapOf("bilibiliBvid" to video.bvid, "bilibiliCid" to cid.toString(), "catalogId" to video.aid.toString())
        return SourceTrackDetails(SourceTrack(TrackIdentity(BILI_SOURCE_ID, account, "${video.bvid}_$cid"), title,
            listOf(video.upName.ifBlank { "哔哩哔哩" }), video.cover.takeIf(String::isNotBlank), data), id,
            album = video.title, durationMs = part?.durationMs?.takeIf { it > 0 } ?: video.durationSec * 1000,
            access = TrackAccessInfo(playable = valid, reason = if (valid) null else "视频已失效或已被删除"),
            payload = SourcePayload("bilibili.track", 1, JSONObject(data).toString()))
    }
    fun bvid(track: SourceTrackDetails): String = track.track.opaqueData["bilibiliBvid"]?.takeIf(String::isNotBlank)
        ?: payload(track.payload).optString("bilibiliBvid").takeIf(String::isNotBlank)
        ?: bvid(track.stableId) ?: bvid(track.track.identity.remoteId) ?: error("缺少有效的哔哩哔哩视频身份")
    fun cid(track: SourceTrackDetails): Long = track.track.opaqueData["bilibiliCid"]?.toLongOrNull()?.takeIf { it > 0 }
        ?: payload(track.payload).optLong("bilibiliCid").takeIf { it > 0 }
        ?: track.stableId.substringAfterLast('_', "").toLongOrNull()?.takeIf { it > 0 } ?: 0
    fun payload(value: SourcePayload): JSONObject = if (value.encoded.isBlank()) JSONObject() else JSONObject(value.encoded)
}
