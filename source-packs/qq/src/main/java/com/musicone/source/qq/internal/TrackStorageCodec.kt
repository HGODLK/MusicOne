package com.musicone.source.qq.internal

import org.json.JSONArray
import org.json.JSONObject

/** 通用曲目持久化；平台原始资料作为不透明字符串保留。 */
internal fun MusicTrack.toStoredTrackJson(): JSONObject = JSONObject()
    .put("source", source.name).put("sourceId", source.id).put("accountId", sourceAccountId)
    .put("remoteKey", remoteKey).put("id", id).put("title", title).put("artists", artists)
    .put("album", album).put("durationMs", durationMs).put("artworkStart", artworkStart)
    .put("artworkEnd", artworkEnd).put("artworkMark", artworkMark).put("catalogId", catalogId)
    .put("mediaId", mediaId).put("albumId", albumId).put("localUri", localUri)
    .put("artistRefs", JSONArray().apply {
        artistRefs.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    })
    .put("providerType", providerType).put("trialAvailable", trialAvailable).put("playable", playable)
    .put("providerData", JSONObject(providerData))
    .apply {
        artworkUrl?.let { put("artworkUrl", it) }
        accessBadge?.let { put("accessBadge", it.name) }
        unavailableReason?.let { put("unavailableReason", it) }
        put("qualityIds", JSONObject().apply {
            this@toStoredTrackJson.qualityIds.forEach { (quality, id) -> put(quality.name, id) }
        })
    }

internal fun JSONObject.toStoredTrack(): MusicTrack? = runCatching {
    // 无source的最早QQ快照保持原兼容规则，不改变既有歌曲与队列身份。
    val storedSource = storedOptionalString("sourceId")?.let(MusicSource::fromId)
        ?: storedOptionalString("source")?.let(MusicSource::valueOf) ?: MusicSource.QQ
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    if (!has("sourceId") && !id.startsWith(storedSource.idPrefix)) return null
    val qualityValues = optJSONObject("qualityIds")
    val qualityIds = buildMap {
        AudioQuality.entries.forEach { quality ->
            qualityValues?.optString(quality.name)?.takeIf(String::isNotBlank)?.let { put(quality, it) }
        }
    }
    val payload = storedProviderData().toMutableMap()
    // 仅兼容读取旧版序列化键；业务含义及类型解析由对应音源包负责。
    listOf("songMid", "bilibiliBvid", "bilibiliCid", "qqAccess").forEach { legacy ->
        if (legacy !in payload && has(legacy) && !isNull(legacy)) payload[legacy] = get(legacy).toString()
    }
    MusicTrack(
        id = id, source = storedSource, title = optString("title").ifBlank { "未知歌曲" },
        artists = optString("artists").ifBlank { "未知歌手" }, album = optString("album"),
        durationMs = optLong("durationMs", 0L).coerceAtLeast(0L), artworkStart = optLong("artworkStart", 0L),
        artworkEnd = optLong("artworkEnd", 0L), artworkMark = optString("artworkMark"), previewUrl = "",
        artworkUrl = storedOptionalString("artworkUrl"), catalogId = optString("catalogId"), mediaId = optString("mediaId"),
        albumId = optString("albumId", optString("albumMid")), localUri = optString("localUri"),
        artistRefs = optJSONArray("artistRefs")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
                MusicArtist(it.optString("id", it.optString("mid")), it.optString("name"))
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.orEmpty(),
        providerData = payload, providerType = optInt("providerType", 0), qualityIds = qualityIds,
        accessBadge = storedOptionalString("accessBadge")?.let { name -> MusicAccessBadge.entries.firstOrNull { it.name == name } },
        trialAvailable = optBoolean("trialAvailable", false), playable = optBoolean("playable", true),
        unavailableReason = storedOptionalString("unavailableReason"),
        sourceAccountId = storedOptionalString("accountId"), remoteKey = optString("remoteKey"),
    )
}.getOrNull()

internal fun JSONObject.storedProviderData(): Map<String, String> = optJSONObject("providerData")?.let { value ->
    value.keys().asSequence().associateWith { value.optString(it) }
}.orEmpty()

private fun JSONObject.storedOptionalString(name: String): String? =
    takeIf { has(name) && !isNull(name) }?.optString(name)?.takeIf(String::isNotBlank)
