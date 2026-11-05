package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 结果页里给模型看的曲目条目。
 * 只留挑歌、说明权益和后续引用所需的字段；整页取值相同的字段提到 itemDefaults，条目里省略。
 * 结果写入历史后不再改写，精简只发生在生成时，不破坏上下文缓存前缀。
 */
internal fun aiResultItems(handle: String, rows: List<Pair<Int, MusicTrack>>): Pair<JSONArray, JSONObject> {
    val items = rows.map { (position, track) ->
        val item = JSONObject().put("trackRef", aiShortTrackRef(handle, position)).put("title", track.title).put("artists", track.artists)
        if (track.album.isNotBlank()) item.put("album", track.album)
        item to aiResultItemFacets(track)
    }
    val defaults = JSONObject()
    aiResultFacetKeys.forEach { key ->
        val values = items.map { it.second[key] }
        val uniform = values.distinct().size == 1
        if (uniform) values.first()?.let { defaults.put(key, it) }
        else items.forEach { (item, facets) -> facets[key]?.let { item.put(key, it) } }
    }
    return JSONArray(items.map { it.first }) to defaults
}

private val aiResultFacetKeys = listOf("sourceId", "playable", "unavailableReason", "trialAvailable", "accessBadge", "badge", "metadataEditable")

// 未声明的权益字段为 null，整页都为 null 时连默认值也不输出。
private fun aiResultItemFacets(track: MusicTrack): Map<String, Any?> = mapOf(
    "sourceId" to track.source.id,
    "playable" to track.playable,
    "unavailableReason" to track.unavailableReason?.takeIf { !track.playable },
    "trialAvailable" to track.trialAvailable.takeIf { it },
    "accessBadge" to track.accessBadge?.name,
    "badge" to track.displayBadge?.text?.takeIf { it.isNotBlank() },
    "metadataEditable" to track.source.supportsLibraryEditing,
)

/** 结果句柄是 UUID，引用只带前 8 位，解析时按前缀唯一匹配；旧对话里的完整引用照常可用。 */
internal fun aiShortTrackRef(handle: String, position: Int): String = "${handle.take(AI_SHORT_HANDLE)}:$position"

internal const val AI_SHORT_HANDLE = 8

/** 拆出引用里的句柄部分和序号；格式不对时返回 null。 */
internal fun aiParseTrackRef(ref: String): Pair<String, Int>? {
    val handle = ref.substringBeforeLast(':', "")
    val index = ref.substringAfterLast(':').toIntOrNull() ?: return null
    return if (handle.isBlank() || index < 0) null else handle to index
}
