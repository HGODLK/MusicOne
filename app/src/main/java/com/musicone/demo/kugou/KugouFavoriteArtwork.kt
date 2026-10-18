package com.musicone.demo

/** 基于原始云端快照叠加收藏操作，失败撤销时封面、歌曲和数量一并恢复。 */
internal class KugouFavoriteArtwork {
    private var cloud: MusicPlaylist? = null
    private var previous: Map<String, Pair<MusicTrack, Boolean>> = emptyMap()
    private val orderedChanges = linkedMapOf<String, Pair<MusicTrack, Boolean>>()

    fun clear() { cloud = null; previous = emptyMap(); orderedChanges.clear() }
    fun record(playlist: MusicPlaylist?) { cloud = playlist }

    fun present(changes: Map<String, Pair<MusicTrack, Boolean>>): MusicPlaylist? {
        val relevant = changes.filterValues { it.first.source == MusicSource.KUGOU }
        orderedChanges.keys.retainAll(relevant.keys)
        relevant.forEach { (id, value) ->
            if (previous[id] != value) {
                orderedChanges.remove(id)
                orderedChanges[id] = value
            }
        }
        previous = relevant
        val playlist = cloud ?: return null
        // 云端已有歌曲的移除撤销保留原位，只有新收藏插到最前面。
        val existingIds = playlist.tracks.mapTo(hashSetOf()) { it.id }
        val kept = playlist.tracks.filterNot { orderedChanges[it.id]?.second == false }
            .map { orderedChanges[it.id]?.first ?: it }
        val additions = orderedChanges.values.toList().asReversed()
            .filter { it.second && it.first.id !in existingIds }.map { it.first }
        val tracks = additions + kept
        return playlist.copy(tracks = tracks,
            count = (playlist.count + tracks.size - playlist.tracks.size).coerceAtLeast(0),
            artworkUrl = tracks.firstOrNull()?.artworkUrl,
        )
    }
}
