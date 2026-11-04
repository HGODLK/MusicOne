package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONArray
import org.json.JSONObject

internal class KugouSourceEntities(
    private val state: () -> KugouPackageState,
    private val client: KugouContentClient = KugouContentClient(),
    private val artistArtwork: KugouArtistArtworkClient = KugouArtistArtworkClient(),
) : SourceEntities {
    override val kinds = setOf("artist", "album")

    override suspend fun entity(reference: SourceEntityRef): SourceEntity {
        val id = platformId(reference)
        val current = state()
        return current.request { session ->
            val request = if (reference.kind == "artist") KugouContentRequest("/kmr/v3/author",
                body = JSONObject().put("author_id", id), tid = "36")
            else client.albumDetailRequest(id)
            val result = client.load(request, session.cookie)
            current.check(session)
            if (reference.kind == "album") return@request result.kugouContentAlbumEntity(id)
            val data = result.opt("data")
            val item = when (data) {
                is JSONArray -> data.optJSONObject(0)
                is JSONObject -> when {
                    data.has("info") -> data.optJSONArray("info")?.optJSONObject(0) ?: data.optJSONObject("info")
                    data.has("list") -> data.optJSONArray("list")?.optJSONObject(0)
                    data.has("author") -> data.optJSONObject("author")
                    else -> data
                }
                else -> null
            } ?: throw PlatformApiException("酷狗没有返回实体详情")
            val entity = item.toKugouContentEntity(reference.kind)
            val backgrounds = runCatchingCancellable { artistArtwork.backgrounds(id, client) }.getOrDefault(emptyList())
            current.check(session)
            if (backgrounds.isEmpty()) entity else entity.copy(attributes = entity.attributes +
                ("backgrounds" to JSONArray(backgrounds).toString()))
        }
    }

    override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        val id = platformId(reference)
        val current = state()
        return current.request { session ->
            // 歌手新版单曲最多100首；专辑接口实测50成功、51返回20010，按平台上限继续完整分页。
            val page = KugouContentPage.from(request, "entity:${reference.kind}:$id:tracks", session.contentOwner(),
                maxSize = if (reference.kind == "artist") 100 else KUGOU_ALBUM_PAGE_SIZE)
            val operation = if (reference.kind == "artist") KugouContentRequest("/openapi/kmr/v2/audio_group/author", null,
                mapOf("author_id" to id, "area_code" to "all", "sort" to "1", "page" to page.page.toString(), "pagesize" to page.size.toString(),
                    "replace_api_version" to "1", "mvdata_need" to "1", "show_audio_honor" to "1", "show_audio_tag" to "1", "replace_need" to "1"), tid = "36")
            else client.albumTracksRequest(id, page.page, page.size)
            val result = client.load(operation, session.cookie)
            current.check(session)
            val rows = result.contentRows("info", "list", "lists", "songs", "audios")
            val next = page.next(result, rows)
            val account = current.account()?.userId
            val vip = current.verifiedVipAccess(session)
            SourcePage(rows.map { item ->
                val track = item.toKugouContentTrack(vip)
                val withReference = if (reference.kind == "artist" && track.artistReferences.none { it.id == id })
                    track.copy(artistReferences = track.artistReferences + reference.copy(id = id))
                else if (reference.kind == "album" && track.albumReference == null) track.copy(albumReference = reference.copy(id = id))
                else track
                KugouSourceMapping.track(withReference, account)
            }, next)
        }
    }

    override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection> {
        val id = platformId(reference)
        // 专辑实体没有下属专辑；这是契约中的正常空分区。
        if (reference.kind == "album") return SourcePage(emptyList(), null)
        val current = state()
        return current.request { session ->
            val page = KugouContentPage.from(request, "entity:artist:$id:albums", session.contentOwner())
            val result = client.load(client.artistAlbumsRequest(id, page.page, page.size), session.cookie)
            current.check(session)
            val rows = result.contentRows("info", "list", "lists", "albums")
            SourcePage(rows.map { it.toKugouArtistAlbumCollection(current.account()?.userId) }, page.next(result, rows))
        }
    }

    private fun platformId(reference: SourceEntityRef): String {
        require(reference.kind in kinds) { "酷狗未接入此实体类型" }
        val id = reference.id.removePrefix("kugou-${reference.kind}:").removePrefix("${reference.kind}:")
        require(id.isNotBlank() && id != "0" && id.all(Char::isDigit)) { "酷狗实体编号无效" }
        return id
    }
}
