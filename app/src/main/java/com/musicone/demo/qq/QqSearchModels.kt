package com.musicone.demo

internal enum class QqSearchTab(val title: String, val type: Int, val field: String) {
    ALL("综合", 100, ""), SONG("单曲", 0, "song"),
    SINGER("歌手", 1, "singer"), ALBUM("专辑", 2, "album"), PLAYLIST("歌单", 3, "songlist"),
}

internal data class QqSearchSinger(
    val id: String,
    val name: String,
    val artwork: String?,
    val numericId: Long = 0L,
)

internal data class QqSearchPage(
    val songs: List<MusicTrack> = emptyList(),
    val singers: List<QqSearchSinger> = emptyList(),
    val albums: List<MusicPlaylist> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val empty get() = songs.isEmpty() && singers.isEmpty() && albums.isEmpty() && playlists.isEmpty()
    fun append(next: QqSearchPage) = next.copy(
        songs = (songs + next.songs).distinctBy { it.id },
        singers = (singers + next.singers).distinctBy { it.id },
        albums = (albums + next.albums).distinctBy { it.id },
        playlists = (playlists + next.playlists).distinctBy { it.id },
    )
}

internal const val QQ_SEARCH_ALBUM_PREFIX = "qq-album-"
internal val MusicPlaylist.isQqSearchAlbum get() = source == MusicSource.QQ && id.startsWith(QQ_SEARCH_ALBUM_PREFIX)

internal fun updatedSearchHistory(history: List<String>, query: String): List<String> {
    val word = query.trim()
    return if (word.isEmpty()) history else (listOf(word) + history.filterNot { it.equals(word, true) }).take(16)
}
