package com.musicone.source.kugou

/** 在云端歌单快照基础上做本地乐观更新（收藏/取消收藏），请求失败时可以整套回滚。 */
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
        // 撤销“取消收藏”时把歌放回原位置，只有新收藏的歌插到最前面
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

