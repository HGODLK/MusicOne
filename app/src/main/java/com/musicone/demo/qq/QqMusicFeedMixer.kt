package com.musicone.demo

import kotlin.math.roundToInt
import kotlin.random.Random
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 按视口估算首屏及下一屏所需的服务端卡位数量。 */
internal fun qqMusicFeedBatchSize(width: Dp, height: Dp): Int {
    val columns = qqMusicFeedColumns(width, height)
    val contentWidth = qqMusicFeedContentMaxWidth(width, height).value
    val gutter = if (width >= 600.dp) 64f else 40f
    val cardWidth = ((contentWidth - gutter - 14f * (columns - 1)) / columns).coerceAtLeast(100f)
    val averageHeight = (cardWidth * 2f + 100f + 300f) / 3f + 20f
    return (columns * height.value.coerceAtLeast(240f) * 2f / averageHeight).roundToInt().coerceIn(6, 24)
}

internal data class QqMusicFeedBatch(
    val visible: List<QqMusicFeedCard>,
    val remaining: List<QqMusicFeedCard>,
)

/** 只在卡片列表变化时整理展示分区，保留各分区内的卡位和对象。 */
internal fun qqMusicFeedDisplayCards(cards: List<QqMusicFeedCard>): List<QqMusicFeedCard> =
    cards.filter { it.section == QqMusicFeedSection.SONG_RECOMMENDATION } +
        cards.filter { it.section == QqMusicFeedSection.MUSIC_FLOW }

/** 后台合并后恢复原有全局分区，三条官方推荐分别合并，横向页和音乐流顺序保持不变。 */
internal fun appendQqMusicFeedDisplayCards(
    old: List<QqMusicFeedCard>, new: List<QqMusicFeedCard>, replace: Boolean,
): List<QqMusicFeedCard> {
    val merged = if (replace) normalizeQqMusicFlowCards(new) else mergeQqMusicFeed(old, new)
    return qqMusicFeedDisplayCards(merged)
}

/**
 * 官方客户端直接按服务端返回的货架和卡片顺序交给瀑布流。
 * 这里不再按歌曲/歌单类型重新编排，否则会把官方推荐序列改成另一套推荐。
 * 同时在所有入口统一去重，避免同一歌单跨页重复出现。
 */
internal fun normalizeQqMusicFlowCards(cards: List<QqMusicFeedCard>): List<QqMusicFeedCard> {
    val seenCardKeys = HashSet<String>()
    val seenPlaylistIds = HashSet<String>()
    val seenPlaylistVisuals = HashSet<String>()
    return combineQqMusicFeedSongShelves(cards).filter { card ->
        if (!seenCardKeys.add(card.key)) return@filter false
        if (card !is QqMusicFeedCard.Playlist) return@filter true

        val remoteId = card.playlist.remoteId().trim().takeIf { it.isNotBlank() }
        if (remoteId != null && !seenPlaylistIds.add(remoteId)) return@filter false

        // 同一推荐歌单偶尔会在不同货架使用不同卡片 ID，封面和标题可作为稳定的二级身份。
        val artwork = card.playlist.artworkUrl.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .lowercase()
        val title = card.playlist.title.qqMusicFeedDedupText()
        if (artwork.isBlank() || title.isBlank()) return@filter true
        seenPlaylistVisuals.add("$title|$artwork")
    }
}

private fun String.qqMusicFeedDedupText(): String = trim().lowercase()
    .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

/** 三行货架和三歌曲组都作为一个完整卡位参与后续分页。 */
internal fun takeQqMusicFeedBatch(
    cards: List<QqMusicFeedCard>,
    limit: Int,
): QqMusicFeedBatch {
    val unique = normalizeQqMusicFlowCards(cards)
    if (limit <= 0) return QqMusicFeedBatch(emptyList(), unique)
    return QqMusicFeedBatch(unique.take(limit), unique.drop(limit))
}

/** 只重排当前已取得的未展示卡池，尽量交错歌曲卡与歌单卡，不为凑类型增加请求。 */
internal fun interleaveQqMusicFeedCards(
    cards: List<QqMusicFeedCard>,
    random: Random = Random.Default,
): List<QqMusicFeedCard> {
    val candidateIndexes = cards.indices.filter { index ->
        when (cards[index]) {
            is QqMusicFeedCard.Playlist,
            is QqMusicFeedCard.Song -> true
            is QqMusicFeedCard.SongShelf,
            is QqMusicFeedCard.SongGroup -> false
        }
    }
    if (candidateIndexes.size < 2) return cards

    val candidates = candidateIndexes.map(cards::get)
    val songs = candidates
        .filterIsInstance<QqMusicFeedCard.Song>()
        .shuffled(random)
        .toMutableList()
    val playlists = candidates
        .filterIsInstance<QqMusicFeedCard.Playlist>()
        .shuffled(random)
        .toMutableList()
    if (songs.isEmpty() || playlists.isEmpty()) return cards
    val interleaved = buildList {
        var lastWasSong: Boolean? = null
        while (songs.isNotEmpty() || playlists.isNotEmpty()) {
            val takeSong = when {
                songs.isEmpty() -> false
                playlists.isEmpty() -> true
                lastWasSong == true -> false
                lastWasSong == false -> true
                songs.size > playlists.size -> true
                playlists.size > songs.size -> false
                else -> random.nextBoolean()
            }
            add(if (takeSong) songs.removeAt(0) else playlists.removeAt(0))
            lastWasSong = takeSong
        }
    }
    return cards.toMutableList().also { result ->
        candidateIndexes.forEachIndexed { index, cardIndex ->
            result[cardIndex] = interleaved[index]
        }
    }
}
