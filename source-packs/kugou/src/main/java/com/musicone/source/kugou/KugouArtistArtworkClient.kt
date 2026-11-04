package com.musicone.source.kugou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

internal data class KugouArtistArtworkSeed(val hash: String, val albumAudioId: String = "", val audioId: String = "")

internal fun interface KugouArtistArtworkTransport {
    suspend fun load(params: Map<String, String>): JSONObject
}

/** 按真实热门曲查询平台写真，不从歌手编号拼造地址，也不读取登录会话。 */
internal class KugouArtistArtworkClient(
    private val transport: KugouArtistArtworkTransport = KugouArtistArtworkTransport { params ->
        runInterruptible(Dispatchers.IO) {
            val url = KugouProtocol.signedAndroidUrl("https://expendablekmr.kugou.com/v2/author_image/audio", params, "")
            val response = PlatformHttp.get(url, headers = mapOf("User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"))
            if (response.status !in 200..299) throw PlatformApiException("酷狗歌手写真读取失败", response.status)
            JSONObject(response.text)
        }
    },
) {
    suspend fun backgrounds(authorId: String, content: KugouContentClient): List<String> {
        val songs = content.load(KugouContentRequest("/openapi/kmr/v2/audio_group/author", null,
            mapOf("author_id" to authorId, "area_code" to "all", "sort" to "1", "page" to "1", "pagesize" to "1",
                "replace_api_version" to "1", "mvdata_need" to "1", "show_audio_honor" to "1", "show_audio_tag" to "1", "replace_need" to "1"),
            tid = "36", publicCatalog = true), "")
        val seed = songs.contentRows("info", "list", "lists", "songs", "audios").firstOrNull()
            ?.kugouArtistArtworkSeed() ?: return emptyList()
        return backgrounds(authorId, seed)
    }

    suspend fun backgrounds(authorId: String, seed: KugouArtistArtworkSeed): List<String> {
        currentCoroutineContext().ensureActive()
        val result = transport.load(requestParams(seed))
        currentCoroutineContext().ensureActive()
        return result.kugouArtistPortraits(authorId)
    }

    internal fun requestParams(seed: KugouArtistArtworkSeed): Map<String, String> {
        require(seed.hash.isNotBlank()) { "酷狗歌手写真缺少歌曲编号" }
        val item = JSONObject().put("audio_id", seed.audioId.ifBlank { "0" }).put("hash", seed.hash)
            .put("album_audio_id", seed.albumAudioId.ifBlank { "0" }).put("filename", "")
        return linkedMapOf("appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
            "count" to "5", "data" to JSONArray().put(item).toString(), "isCdn" to "1", "publish_time" to "1", "show_authors" to "1")
    }
}

internal fun JSONObject.kugouArtistArtworkSeed(): KugouArtistArtworkSeed? {
    val audio = optJSONObject("audio_info")
    val base = optJSONObject("base")
    val hash = contentText("hash", "FileHash", "Hash").ifBlank { audio?.contentText("hash").orEmpty() }
    if (hash.isBlank() || hash.all { it == '0' }) return null
    return KugouArtistArtworkSeed(hash,
        contentText("album_audio_id", "mixsongid", "MixSongID").ifBlank { base?.contentText("album_audio_id").orEmpty() },
        contentText("audio_id").ifBlank { base?.contentText("audio_id").orEmpty() })
}

/** 接口实证 data[歌曲][作者].imgs[类型]；仅选目标作者，去重后把首张移到末尾。 */
internal fun JSONObject.kugouArtistPortraits(authorId: String): List<String> {
    requireKugouContentSuccess(this)
    val groups = optJSONArray("data") ?: throw PlatformApiException("酷狗歌手写真响应格式错误")
    val urls = mutableListOf<String>()
    for (groupIndex in 0 until groups.length()) {
        val authors = groups.optJSONArray(groupIndex) ?: throw PlatformApiException("酷狗歌手写真作者列表格式错误")
        authors.contentObjects().filter { it.contentText("author_id") == authorId }.forEach { author ->
            val images = author.optJSONObject("imgs") ?: return@forEach
            // 保留平台返回的分组及组内顺序，不另外按类型或头像特征重排。
            images.keys().forEach { type ->
                    images.optJSONArray(type)?.contentObjects()?.forEach { item ->
                        item.contentText("sizable_portrait").normalizeKugouContentArtwork(1080)?.let(urls::add)
                    }
                }
        }
    }
    val unique = urls.distinct()
    return if (unique.size > 1) unique.drop(1) + unique.first() else unique
}
