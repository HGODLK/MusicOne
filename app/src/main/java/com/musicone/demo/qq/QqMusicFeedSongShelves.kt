package com.musicone.demo

internal const val QQ_OFFICIAL_RECOMMENDATION_LIMIT = 3

/** 三条官方推荐各自保留稳定身份，只合并同一货架的横向页。 */
internal fun combineQqMusicFeedSongShelves(cards: List<QqMusicFeedCard>): List<QqMusicFeedCard> {
    val result = mutableListOf<QqMusicFeedCard>()
    val shelfIndexes = mutableMapOf<String, Int>()
    val pageKeys = mutableMapOf<String, MutableSet<List<String>>>()
    for (card in cards) {
        if (!card.isOfficialQqSongShelf()) {
            result += card
            continue
        }
        card as QqMusicFeedCard.SongShelf
        val shelfIndex = shelfIndexes[card.key]
        if (shelfIndex == null && shelfIndexes.size >= QQ_OFFICIAL_RECOMMENDATION_LIMIT) continue
        val seenPages = pageKeys.getOrPut(card.key) { hashSetOf() }
        val newPages = card.pages.filter { page ->
            page.isNotEmpty() && seenPages.add(page.qqSongShelfPageKey())
        }
        if (newPages.isEmpty()) continue
        if (shelfIndex == null) {
            shelfIndexes[card.key] = result.size
            result += if (newPages == card.pages) card else card.copy(pages = newPages)
        } else {
            val first = result[shelfIndex] as QqMusicFeedCard.SongShelf
            result[shelfIndex] = first.copy(pages = first.pages + newPages)
        }
    }
    return result
}

/** 稳定容器的 key 不能用于过滤新页；只过滤已经展示的完整歌曲组，允许组间歌曲重复。 */
internal fun unseenQqMusicFeedCards(
    incoming: List<QqMusicFeedCard>,
    displayed: List<QqMusicFeedCard>,
): List<QqMusicFeedCard> {
    val seen = displayed.filterNot { it.isOfficialQqSongShelf() }.mapTo(hashSetOf(), QqMusicFeedCard::key)
    val seenShelves = displayed.filterIsInstance<QqMusicFeedCard.SongShelf>()
        .filter { it.isOfficialQqSongShelf() }
        .associateBy { it.key }
    return incoming.mapNotNull { card ->
        if (card.isOfficialQqSongShelf()) {
            card as QqMusicFeedCard.SongShelf
            if (card.key !in seenShelves && seenShelves.size >= QQ_OFFICIAL_RECOMMENDATION_LIMIT) return@mapNotNull null
            val seenPages = seenShelves[card.key]?.pages.orEmpty().mapTo(hashSetOf()) { it.qqSongShelfPageKey() }
            val pages = card.pages.filter { page -> page.isNotEmpty() && page.qqSongShelfPageKey() !in seenPages }
            when {
                pages.isEmpty() -> null
                pages == card.pages -> card
                else -> card.copy(pages = pages)
            }
        } else card.takeUnless { it.key in seen }
    }
}

private fun List<QqMusicFeedCard.Song>.qqSongShelfPageKey(): List<String> = map {
    "${it.track.providerType}:${it.track.catalogId.ifBlank { it.track.id }}"
}

private fun QqMusicFeedCard.isOfficialQqSongShelf(): Boolean =
    this is QqMusicFeedCard.SongShelf && section == QqMusicFeedSection.SONG_RECOMMENDATION
