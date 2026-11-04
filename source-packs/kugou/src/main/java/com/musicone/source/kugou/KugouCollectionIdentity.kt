package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCollection
import org.json.JSONObject

internal fun kugouCollectionFavoriteKeys(collection: SourceCollection): Set<String> =
    kugouCollectionFavoriteKeys(collection.stableId, runCatching { JSONObject(collection.payload.encoded) }.getOrNull())

internal fun kugouCollectionFavoriteKeys(stableId: String, data: JSONObject?): Set<String> = buildSet {
    add(stableId)
    if (data == null) return@buildSet
    listOf("global_collection_id", "list_create_gid").forEach { field ->
        data.optString(field).takeIf { it.isNotBlank() && it != "0" && it != "null" }?.let { add("global:$it") }
    }
    val owner = data.optString("list_create_userid")
    val list = data.optString("list_create_listid")
    if (owner.toLongOrNull()?.let { it > 0 } == true && list.toLongOrNull()?.let { it > 0 } == true) add("creator:$owner:$list")
}
