package com.musicone

/** 分组数量由音源声明；同一容器追加横向页，不把相同容器的新页按卡片 key 丢弃。 */
internal fun combineMainSourceHomeShelves(cards: List<MainSourceHomeCard>): List<MainSourceHomeCard> {
    val result = mutableListOf<MainSourceHomeCard>()
    val indexes = mutableMapOf<String, Int>()
    val pages = mutableMapOf<String, MutableSet<List<String>>>()
    val groups = mutableMapOf<String, MutableSet<String>>()
    for (card in cards) {
        if (card !is MainSourceHomeCard.SongShelf || !card.mergePages) { result += card; continue }
        val index = indexes[card.key]
        val group = card.groupId?.let { groups.getOrPut(it) { hashSetOf() } }
        if (index == null && group != null && card.groupLimit?.let { group.size >= it.coerceAtLeast(0) } == true) continue
        val seen = pages.getOrPut(card.key) { hashSetOf() }
        val additions = card.pages.filter { it.isNotEmpty() && seen.add(it.mainSourceShelfPageKey()) }
        if (additions.isEmpty()) continue
        if (index == null) {
            group?.add(card.key)
            indexes[card.key] = result.size
            result += if (additions == card.pages) card else card.copy(pages = additions)
        } else {
            val first = result[index] as MainSourceHomeCard.SongShelf
            result[index] = first.copy(pages = first.pages + additions)
        }
    }
    return result
}

internal fun unseenMainSourceHomeCards(incoming: List<MainSourceHomeCard>, displayed: List<MainSourceHomeCard>): List<MainSourceHomeCard> {
    val seen = displayed.filterNot { it is MainSourceHomeCard.SongShelf && it.mergePages }.mapTo(hashSetOf(), MainSourceHomeCard::key)
    val shelves = displayed.filterIsInstance<MainSourceHomeCard.SongShelf>().filter { it.mergePages }.associateBy { it.key }.toMutableMap()
    return combineMainSourceHomeShelves(incoming).mapNotNull { card ->
        if (card !is MainSourceHomeCard.SongShelf || !card.mergePages) card.takeUnless { it.key in seen }
        else {
            val groupCount = shelves.values.count { card.groupId != null && it.groupId == card.groupId }
            if (card.key !in shelves && card.groupId != null && card.groupLimit?.let { groupCount >= it } == true) return@mapNotNull null
            val pageKeys = shelves[card.key]?.pages.orEmpty().mapTo(hashSetOf()) { it.mainSourceShelfPageKey() }
            val additions = card.pages.filter { it.isNotEmpty() && pageKeys.add(it.mainSourceShelfPageKey()) }
            if (additions.isEmpty()) null else {
                shelves[card.key] = card.copy(pages = shelves[card.key]?.pages.orEmpty() + additions)
                if (additions == card.pages) card else card.copy(pages = additions)
            }
        }
    }
}

/** 完整横向页为排重单位；不同推荐组之间允许出现同一首歌曲。 */
private fun List<MainSourceHomeCard.Song>.mainSourceShelfPageKey(): List<String> = map {
    val track = it.track
    "${track.source.id}:${track.providerType}:${track.catalogId.ifBlank { track.remoteKey.ifBlank { track.id } }}"
}
