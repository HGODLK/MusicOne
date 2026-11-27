package com.musicone.source.webdav

import com.musicone.source.sdk.SourceCollectionSortOption
import com.musicone.source.sdk.SourcePlayerState
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.webdav.internal.WEBDAV_SOURCE_ID
import com.musicone.source.webdav.internal.WebDavEntry
import com.musicone.source.webdav.internal.WebDavIdentity
import com.musicone.source.webdav.internal.WebDavPaths
import java.text.Collator
import java.util.Locale

/** 单个目录的完整界面状态，切目录时整体替换，避免新路径下闪出旧目录内容。 */
internal data class WebDavDirectoryView(
    val serverKey: String = "",
    val path: String = "",
    val directories: List<WebDavEntry> = emptyList(),
    val tracks: List<SourceTrackDetails> = emptyList(),
    val coverUri: String? = null,
    /** 是否已经加载过目录内容；loading 和 loaded 同时为 true 时表示正在后台刷新。 */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

internal data class DirectorySummary(val subdirCount: Int, val audioCount: Int)

internal data class WebDavScrollPosition(val index: Int = 0, val offset: Int = 0)

internal data class WebDavFeedback(val id: Long, val message: String, val isError: Boolean)

internal enum class WebDavTrackSort(val label: String) {
    FILE("文件顺序"), TITLE("歌名"), ARTIST("歌手");

    companion object {
        val options: List<SourceCollectionSortOption> = entries.map { SourceCollectionSortOption(it.name, it.label) }
        fun of(id: String): WebDavTrackSort = entries.firstOrNull { it.name == id } ?: FILE
    }
}

internal fun sortedWebDavTracks(tracks: List<SourceTrackDetails>, sort: WebDavTrackSort): List<SourceTrackDetails> {
    if (sort == WebDavTrackSort.FILE) return tracks
    val collator = Collator.getInstance(Locale.CHINA)
    val byTitle = Comparator<SourceTrackDetails> { a, b -> collator.compare(a.track.title, b.track.title) }
    if (sort == WebDavTrackSort.TITLE) return tracks.sortedWith(byTitle)
    // 没有歌手的排到最后。
    return tracks.sortedWith(Comparator { a, b ->
        val blank = a.artistsText.isBlank().compareTo(b.artistsText.isBlank())
        if (blank != 0) return@Comparator blank
        val artist = collator.compare(a.artistsText, b.artistsText)
        if (artist != 0) artist else byTitle.compare(a, b)
    })
}

internal fun List<SourceTrackDetails>.matchingWebDavQuery(query: String): List<SourceTrackDetails> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    return filter {
        it.track.title.contains(keyword, ignoreCase = true) ||
            it.artistsText.contains(keyword, ignoreCase = true) ||
            it.album.contains(keyword, ignoreCase = true)
    }
}

internal fun webDavCountText(directories: Int, tracks: Int): String = when {
    directories > 0 && tracks > 0 -> "$directories 个文件夹 · $tracks 首歌曲"
    directories > 0 -> "$directories 个文件夹"
    tracks > 0 -> "$tracks 首歌曲"
    else -> "没有歌曲"
}

internal fun webDavDepth(path: String): Int = WebDavPaths.breadcrumbs(path, "").size - 1

/** 平板目录树的可见行。 */
internal data class WebDavTreeNode(
    val path: String,
    val label: String,
    val depth: Int,
    val expanded: Boolean,
    val leaf: Boolean,
)

internal fun buildWebDavTree(
    rootLabel: String,
    children: Map<String, List<WebDavEntry>>,
    expanded: Map<String, Boolean>,
): List<WebDavTreeNode> {
    val result = ArrayList<WebDavTreeNode>()
    fun visit(path: String, label: String, depth: Int) {
        val known = children[path]
        val open = expanded[path] == true
        result += WebDavTreeNode(path, label, depth, open, leaf = known != null && known.isEmpty())
        if (open) known.orEmpty().forEach { visit(it.path, it.name, depth + 1) }
    }
    visit("", rootLabel, 0)
    return result
}

/** 从根到目标的所有上级目录（不含目标本身）。 */
internal fun webDavAncestors(path: String): List<String> =
    WebDavPaths.breadcrumbs(path, "").dropLast(1).map { it.second }

/**
 * playerState.stableTrackId 是宿主曲目 ID，按“来源＋账号＋包内 ID”哈希得到，不能与包内 stableId 直接比较。
 * 这里按同一规则换算，只用于高亮和定位；宿主规则若变化，最多失去高亮，不影响播放与资料。
 */
internal fun webDavHostTrackId(track: SourceTrackDetails): String {
    val account = track.track.identity.accountId.orEmpty()
    val input = "${account.length}:$account:${track.stableId.length}:${track.stableId}"
    return WEBDAV_SOURCE_ID + ":" + WebDavIdentity.sha256Hex(input)
}

/** 查找当前正在播放的歌曲在包内的 ID；hostIds 是宿主曲目 ID 到包内 ID 的映射表。 */
internal fun playingWebDavTrackId(player: SourcePlayerState, hostIds: Map<String, String>): String? {
    if (player.sourceId != WEBDAV_SOURCE_ID) return null
    return player.stableTrackId?.let(hostIds::get)
}
