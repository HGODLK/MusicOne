package com.musicone

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import org.json.JSONObject

/** 公共模板只消费 SDK 已完成的首页分区，不参与平台协议分页或元数据补查。 */
internal class MainSourceHomePager(
    private val load: suspend (SourceCursorRequest) -> SourcePage<SourceHomeSection>,
    private val presentation: (SourceHomeSection) -> SourceHomePresentation? = { null },
    private val buffered: (String) -> Boolean = { false },
) {
    constructor(context: Context, source: MusicSource) : this(
        load = { request -> requireNotNull(SourcePackageController.get(context).running(source.id)?.entry?.discovery) {
            "此音源未启用推荐"
        }.home(request) },
        presentation = { section -> (SourcePackageController.get(context).running(source.id)?.entry?.discovery
            as? SourceHomeSectionPresentation)?.homePresentation(section) },
        buffered = { cursor -> (SourcePackageController.get(context).running(source.id)?.entry?.discovery
            as? SourceHomeContinuation)?.hasBufferedHomeItems(cursor) == true },
    )
    private var started = false
    private var cursor: String? = null
    private var pending = emptyList<MainSourceHomeCard>()
    val finished get() = started && cursor == null && pending.isEmpty()
    val hasBufferedItems get() = pending.isNotEmpty() || cursor?.let(buffered) == true
    suspend fun next(displayed: List<MainSourceHomeCard>, size: Int, replace: Boolean): MainSourceHomeBatch {
        if (size <= 0) return MainSourceHomeBatch(emptyList(), pending)
        if (replace) { started = false; cursor = null; pending = emptyList() }
        val previous = if (replace) emptyList() else displayed
        pending = normalizeMainSourceHomeCards(unseenMainSourceHomeCards(pending, previous))
        var requests = 0
        // 重复页可以继续沿有效游标探测；刷新不使用旧画面作为排重集合。
        while (pending.isEmpty() && !finished && requests++ < if (replace || displayed.isEmpty()) 3 else 8) {
            val page = load(SourceCursorRequest(cursor, size, if (replace) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST))
            check(page.nextCursor == null || page.nextCursor != cursor) { "音源推荐分页没有前进" }
            started = true; cursor = page.nextCursor
            pending = normalizeMainSourceHomeCards(unseenMainSourceHomeCards(page.items.flatMap { cards(it, presentation(it)) }, previous))
        }
        // 音源已按自身卡池完成编排；这里保留交付顺序，避免同一批在宿主再次随机排列。
        val arranged = mainSourceHomeDisplayCards(pending)
        val selected = arranged.take(size.coerceAtLeast(1))
        pending = arranged.drop(selected.size)
        return MainSourceHomeBatch(selected, pending)
    }
    private fun cards(section: SourceHomeSection, semantics: SourceHomePresentation?): List<MainSourceHomeCard> {
        val presentation = if (section.payload.format == "musicone.home-section" && section.payload.encoded.isNotBlank()) JSONObject(section.payload.encoded) else JSONObject()
        val layout = presentation.optString("layout")
        // 这两种公共分区由首页现有独立卡片呈现，避免在推荐流重复。
        if (layout == "daily" || layout == "recent") return emptyList()
        val tracks = section.tracks.map { it.toMusicTrack() }
        val titles = presentation.optJSONArray("recommendationTitles")?.let { values -> (0 until values.length()).map(values::optString) }.orEmpty()
        val collections = section.collections.map { MainSourceHomeCard.Playlist(it.toMusicPlaylist()) }
        val songs = when {
            layout == "track-shelf" -> {
                val sizes = presentation.optJSONArray("pageSizes")
                var offset = 0
                val pages = if (sizes != null) (0 until sizes.length()).map { index ->
                    val count = sizes.optInt(index).coerceAtLeast(0)
                    tracks.drop(offset).take(count).mapIndexed { position, track -> MainSourceHomeCard.Song(track, titles.getOrNull(offset + position).orEmpty()) }.also { offset += count }
                } else listOf(tracks.map { MainSourceHomeCard.Song(it, section.title) })
                listOf(MainSourceHomeCard.SongShelf(section.id, section.title, pages,
                    section = if (semantics?.recommendation != false) MainSourceHomeSectionKind.SONG_RECOMMENDATION else MainSourceHomeSectionKind.MUSIC_FLOW,
                    mergePages = semantics?.mergePages ?: true, stableIdentity = semantics?.stableId,
                    groupId = semantics?.groupId, groupLimit = semantics?.groupLimit))
            }
            layout == "track-group" -> listOf(MainSourceHomeCard.SongGroup(section.title, tracks, titles))
            else -> tracks.map { MainSourceHomeCard.Song(it, section.title) }
        }
        return collections + songs
    }
}
