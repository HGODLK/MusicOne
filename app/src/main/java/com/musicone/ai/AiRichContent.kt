package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 聊天里的临时歌单和操作回执单独序列化，不写进本地音乐库。 */
internal fun MusicPlaylist.toAiPlaylistJson(): JSONObject = JSONObject()
    .put("id", id).put("sourceId", source.id).put("title", title).put("subtitle", subtitle)
    .put("description", description).put("count", count).put("start", artworkStart).put("end", artworkEnd)
    .put("mark", artworkMark).put("artwork", artworkUrl).put("owned", isOwned)
    .put("favorites", isPlatformFavorites).put("providerData", JSONObject(providerData))
    .put("accountId", sourceAccountId).put("remoteKey", remoteKey)
    .put("tracks", JSONArray(tracks.map { it.toStoredTrackJson() }))

internal fun JSONObject.toAiPlaylist(): MusicPlaylist = MusicPlaylist(getString("id"), MusicSource.fromId(getString("sourceId")),
    getString("title"), optString("subtitle"), optString("description"), optInt("count"), optLong("start"), optLong("end"),
    optString("mark"), optJSONArray("tracks")?.objects().orEmpty().mapNotNull { it.toStoredTrack() }, optional("artwork"),
    isOwned = optBoolean("owned"), isPlatformFavorites = optBoolean("favorites"),
    providerData = optJSONObject("providerData")?.let { data -> data.keys().asSequence().associateWith { data.getString(it) } }.orEmpty(),
    sourceAccountId = optional("accountId"), remoteKey = optString("remoteKey"))

internal fun AiReceiptUi.toAiReceiptJson(): JSONObject = JSONObject().put("summary", summary)
    .put("groups", JSONArray(groups.map { group -> JSONObject().put("title", group.title)
        .put("fields", JSONArray(group.fields.map { field -> JSONObject().put("label", field.label)
            .put("before", field.before).put("after", field.after) })) }))

internal fun JSONObject.toAiReceipt() = AiReceiptUi(optString("summary"), optJSONArray("groups")?.objects().orEmpty().map { group ->
    AiChangeGroupUi(group.optString("title"), group.optJSONArray("fields")?.objects().orEmpty().map {
        AiFieldChangeUi(it.optString("label"), it.optString("before"), it.optString("after"))
    })
})

/** 待展示的歌曲卡片里有音源私有数据，只在本地恢复界面用，不能发给模型。 */
internal fun JSONObject.aiPublicCheckpoint(): JSONObject = JSONObject(toString()).apply {
    remove("pendingCards")
    // token 用量和诊断信息只留本地，不发给模型。
    remove("actualUsage")
    remove("contextTokenSample")
    remove("lastSearchAttempt")
}

/** 工具查到的歌曲卡片先暂存起来，等正文输出完再一起显示，避免卡片抢跑。 */
internal class AiDeferredCards(private val checkpoint: JSONObject) {
    fun stage(tracks: List<MusicTrack>, reason: String) {
        val pending = data()
        val existing = pending.optJSONArray("tracks")?.objects().orEmpty().mapNotNull { it.toStoredTrack() }
        pending.put("tracks", JSONArray((existing + tracks).distinctBy { it.source.id to it.id }.map { it.toStoredTrackJson() }))
            .put("reason", reason)
    }

    fun stage(playlist: MusicPlaylist) {
        val pending = data()
        pending.put("playlist", playlist.toAiPlaylistJson())
        pending.remove("tracks")
    }

    fun finish(): AiChatMessageUi? {
        val pending = checkpoint.optJSONObject("pendingCards") ?: return null
        if (pending.optString("turnId") != checkpoint.optString("activeTurnId")) return null
        val playlist = pending.optJSONObject("playlist")?.toAiPlaylist()
        val tracks = (playlist?.tracks.orEmpty() + pending.optJSONArray("tracks")?.objects().orEmpty().mapNotNull { it.toStoredTrack() })
            .distinctBy { it.source.id to it.id }
        val combined = when {
            playlist != null && pending.optJSONArray("tracks")?.length().let { it == null || it == 0 } -> playlist
            playlist != null -> aiTemporaryPlaylist("ai-turn-${pending.getString("turnId")}",
                checkpoint.optString("displayRecommendationTitle").ifBlank { playlist.title }, pending.optString("reason"), tracks)
            tracks.size >= 5 -> aiTemporaryPlaylist("ai-turn-${pending.getString("turnId")}",
                checkpoint.optString("displayRecommendationTitle").ifBlank { "为你挑选的音乐" }, pending.optString("reason"), tracks)
            else -> null
        }
        checkpoint.remove("pendingCards")
        return AiChatMessageUi(pending.getString("turnId"), "assistant", "", tracks = if (combined == null) tracks else emptyList(), playlist = combined)
    }

    private fun data(): JSONObject {
        val turn = checkpoint.getString("activeTurnId")
        return checkpoint.optJSONObject("pendingCards")?.takeIf { it.optString("turnId") == turn }
            ?: JSONObject().put("turnId", turn).also { checkpoint.put("pendingCards", it) }
    }
}
