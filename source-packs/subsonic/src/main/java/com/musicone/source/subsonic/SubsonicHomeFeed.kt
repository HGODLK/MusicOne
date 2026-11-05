package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页按固定步骤分页：随机推荐货架和最新专辑在前，随后是常听、最近播放、收藏的专辑，
 * 最后是若干页随机专辑。Subsonic 没有个性化推荐，标题如实说明来源；不生成收藏歌曲卡片。
 */
internal class SubsonicHomeFeed(private val client: SubsonicClient, private val urls: SubsonicArtworkUrls) {
    private data class Step(val type: String, val label: String)

    private val steps = listOf(
        Step("newest", "最新加入"), Step("frequent", "常听"), Step("recent", "最近播放"), Step("starred", "收藏的专辑"),
    ) + List(RANDOM_ALBUM_PAGES) { Step("random", "随机专辑") }

    /** 返回一页首页卡片；某一步没有内容时继续下一步，避免交出空页。 */
    fun page(server: SubsonicServer, cursor: String?): SourcePage<SourceHomeSection> {
        var step = cursor?.toIntOrNull()?.coerceIn(0, steps.size) ?: 0
        val sections = mutableListOf<SourceHomeSection>()
        if (step == 0) randomShelf(server)?.let(sections::add)
        while (step < steps.size) {
            val current = steps[step]
            step++
            val albums = client.get(server, "getAlbumList2", listOf("type" to current.type, "size" to ALBUMS_PER_STEP.toString()))
                .optJSONObject("albumList2")?.objects("album").orEmpty().mapNotNull { it.album() }
            albums.forEach { album ->
                val subtitle = listOf(current.label, album.artist).filter(String::isNotBlank).joinToString(" · ")
                sections += SourceHomeSection("${current.type}:${album.id}", album.name,
                    collections = listOf(album.collection(server, urls, subtitle)), payload = layout("collection-card"))
            }
            if (sections.isNotEmpty()) break
        }
        return SourcePage(sections, step.takeIf { it < steps.size }?.toString())
    }

    private fun randomShelf(server: SubsonicServer): SourceHomeSection? {
        val songs = randomSongs(server, SHELF_PAGE_SIZE * SHELF_PAGES)
        if (songs.isEmpty()) return null
        val pages = songs.chunked(SHELF_PAGE_SIZE).map { it.size }
        return SourceHomeSection("random-songs", "随机推荐", songs.map { it.details(server, urls) },
            payload = layout("track-shelf", JSONObject().put("pageSizes", JSONArray(pages))))
    }

    fun randomSongs(server: SubsonicServer, size: Int): List<SubsonicSong> =
        client.get(server, "getRandomSongs", listOf("size" to size.toString()))
            .optJSONObject("randomSongs")?.objects("song").orEmpty().mapNotNull { it.song() }

    fun presentation(section: SourceHomeSection): SourceHomePresentation =
        if (section.id == "random-songs") SourceHomePresentation(section.id, mergePages = false, recommendation = true)
        else SourceHomePresentation(section.id)

    private fun layout(type: String, extra: JSONObject = JSONObject()) =
        SourcePayload("musicone.home-section", 1, extra.put("layout", type).toString())

    private companion object {
        const val ALBUMS_PER_STEP = 8
        const val RANDOM_ALBUM_PAGES = 6
        const val SHELF_PAGE_SIZE = 3
        const val SHELF_PAGES = 4
    }
}
