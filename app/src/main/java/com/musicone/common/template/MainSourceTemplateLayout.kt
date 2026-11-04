package com.musicone

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class MainSourceHomeBatch(val visible: List<MainSourceHomeCard>, val remaining: List<MainSourceHomeCard>)
internal fun mainSourceHomeDisplayCards(cards: List<MainSourceHomeCard>): List<MainSourceHomeCard> =
    cards.filter { it.section == MainSourceHomeSectionKind.SONG_RECOMMENDATION } +
        cards.filter { it.section == MainSourceHomeSectionKind.MUSIC_FLOW }
internal fun normalizeMainSourceHomeCards(cards: List<MainSourceHomeCard>): List<MainSourceHomeCard> {
    val seen = hashSetOf<String>()
    val playlists = hashSetOf<String>()
    val visuals = hashSetOf<String>()
    return combineMainSourceHomeShelves(cards).filter { card ->
        if (!seen.add(card.key)) return@filter false
        if (card !is MainSourceHomeCard.Playlist) return@filter true
        val playlist = card.playlist
        val identity = "${playlist.source.id}:${playlist.sourceAccountId}:${playlist.remoteId()}"
        if (!playlists.add(identity)) return@filter false
        val artwork = playlist.artworkUrl.orEmpty().substringBefore('?').substringBefore('#').trim().lowercase()
        val title = playlist.title.trim().lowercase().replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        artwork.isBlank() || title.isBlank() || visuals.add("${playlist.source.id}:$title|$artwork")
    }
}
/** 只整理已有卡池，独立推荐货架保持原位，不为凑卡型额外请求。 */
internal fun interleaveMainSourceHomeCards(cards: List<MainSourceHomeCard>, random: Random = Random.Default): List<MainSourceHomeCard> {
    val indexes = cards.indices.filter { cards[it] is MainSourceHomeCard.Song || cards[it] is MainSourceHomeCard.Playlist }
    val songs = indexes.map(cards::get).filterIsInstance<MainSourceHomeCard.Song>().shuffled(random).toMutableList()
    val playlists = indexes.map(cards::get).filterIsInstance<MainSourceHomeCard.Playlist>().shuffled(random).toMutableList()
    if (songs.isEmpty() || playlists.isEmpty()) return cards
    val arranged = buildList<MainSourceHomeCard> {
        var previousSong: Boolean? = null
        while (songs.isNotEmpty() || playlists.isNotEmpty()) {
            val song = when {
                songs.isEmpty() -> false
                playlists.isEmpty() -> true
                previousSong == true -> false
                previousSong == false -> true
                songs.size > playlists.size -> true
                playlists.size > songs.size -> false
                else -> random.nextBoolean()
            }
            add(if (song) songs.removeAt(0) else playlists.removeAt(0))
            previousSong = song
        }
    }
    return cards.toMutableList().also { result -> indexes.forEachIndexed { index, position -> result[position] = arranged[index] } }
}
internal fun appendMainSourceHomeDisplayCards(old: List<MainSourceHomeCard>, new: List<MainSourceHomeCard>, replace: Boolean) =
    mainSourceHomeDisplayCards(normalizeMainSourceHomeCards((if (replace) emptyList() else old) + new))
internal fun mainSourceHomeBatchSize(width: Dp, height: Dp): Int {
    val columns = mainSourceHomeColumns(width, height)
    val contentWidth = mainSourceHomeContentMaxWidth(width, height).value
    val gutter = if (width >= 600.dp) 64f else 40f
    val cardWidth = ((contentWidth - gutter - 14f * (columns - 1)) / columns).coerceAtLeast(100f)
    val averageHeight = (cardWidth * 2f + 100f + 300f) / 3f + 20f
    return (columns * height.value.coerceAtLeast(240f) * 2f / averageHeight).roundToInt().coerceIn(6, 24)
}
internal fun updatedMainSourceSearchHistory(history: List<String>, query: String): List<String> {
    val word = query.trim()
    return if (word.isEmpty()) history else (listOf(word) + history.filterNot { it.equals(word, true) }).take(16)
}
