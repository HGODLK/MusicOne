package com.musicone

internal enum class MainSourceHomeSectionKind {
    SONG_RECOMMENDATION,
    MUSIC_FLOW,
}

internal sealed interface MainSourceHomeCard {
    val key: String
    val section: MainSourceHomeSectionKind

    data class Playlist(
        val playlist: MusicPlaylist,
        override val section: MainSourceHomeSectionKind = MainSourceHomeSectionKind.MUSIC_FLOW,
    ) : MainSourceHomeCard {
        override val key = "playlist:${playlist.id}"
    }

    data class Song(
        val track: MusicTrack,
        val recommendationTitle: String,
        val lookupQuery: String? = null,
        override val section: MainSourceHomeSectionKind = MainSourceHomeSectionKind.MUSIC_FLOW,
    ) : MainSourceHomeCard {
        override val key = "song:${track.id}"
    }

    /** 官方三行货架：一个横向页面对应服务端返回的一组歌曲。 */
    data class SongShelf(
        val shelfId: String,
        val title: String,
        val pages: List<List<Song>>,
        override val section: MainSourceHomeSectionKind = MainSourceHomeSectionKind.SONG_RECOMMENDATION,
        val mergePages: Boolean = true,
        val stableIdentity: String? = null,
        val groupId: String? = null,
        val groupLimit: Int? = null,
    ) : MainSourceHomeCard {
        // 同一货架换歌仍保留身份；同 ID 下的不同主题标题是独立推荐，不能合并成一栏。
        override val key = stableIdentity?.let { if (it.startsWith("song-shelf:")) it else "song-shelf:$it" }
            ?: if (shelfId.startsWith("song-shelf:")) shelfId else if (section == MainSourceHomeSectionKind.SONG_RECOMMENDATION) {
            "song-shelf:$shelfId:${title.trim().replace(Regex("\\s+"), " ")}"
        } else "song-shelf:${shelfId.ifBlank { title }}:${pages.flatten().joinToString(",") { it.track.id }}"
    }

    data class SongGroup(
        val recommendationTitle: String,
        val tracks: List<MusicTrack>,
        val trackRecommendationTitles: List<String> = emptyList(),
        override val section: MainSourceHomeSectionKind = MainSourceHomeSectionKind.MUSIC_FLOW,
    ) : MainSourceHomeCard {
        override val key = "song-group:${tracks.joinToString(",") { it.id }}"
    }
}

internal enum class MainSourceSearchTab(val title: String) {
    ALL("综合"), SONG("单曲"),
    SINGER("歌手"), ALBUM("专辑"), PLAYLIST("歌单"),
}

internal data class MainSourceArtist(
    val id: String,
    val name: String,
    val artwork: String?,
    val source: MusicSource,
    val reference: com.musicone.source.sdk.SourceEntityRef? = null,
)

internal data class MainSourceSearchPage(
    val songs: List<MusicTrack> = emptyList(),
    val singers: List<MainSourceArtist> = emptyList(),
    val albums: List<MusicPlaylist> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val empty get() = songs.isEmpty() && singers.isEmpty() && albums.isEmpty() && playlists.isEmpty()
    fun append(next: MainSourceSearchPage) = next.copy(
        songs = (songs + next.songs).distinctBy { it.id },
        singers = (singers + next.singers).distinctBy { it.id },
        albums = (albums + next.albums).distinctBy { it.id },
        playlists = (playlists + next.playlists).distinctBy { it.id },
    )
}

internal data class MainSourceSimilarSong(
    val track: MusicTrack,
)

internal data class MainSourceSimilarRecommendation(
    val baseTrack: MusicTrack,
    val title: String,
    val songs: List<MainSourceSimilarSong>,
)



internal data class MainSourceRecentSnapshot(val songs: List<MusicTrack> = emptyList(), val songCount: Int = 0,
    val playlists: List<MusicPlaylist> = emptyList(), val source: MusicSource = MusicSource.LOCAL)
internal data class MainSourceArtistSearchPage(val items: List<MusicTrack>, val nextOffset: Int?, val total: Int,
    val customInfo: org.json.JSONObject = org.json.JSONObject())
internal const val MAIN_SOURCE_RECENT_WINDOW_SIZE = 12
internal const val MAIN_SOURCE_SIMILAR_SIZE = 3
internal const val MAIN_SOURCE_SIMILAR_REQUEST_SIZE = 6
internal const val MAIN_SOURCE_SIMILAR_PAGE_COUNT = 3
internal fun MusicTrack.isMainSourceFavorite(ids: Set<String>) = playlistTrackIdentities(this).any(ids::contains)
internal val MusicPlaylist.isSourceAlbum: Boolean get() = providerData["templateEntityKind"] == "album"
internal fun MainSourceRecentSnapshot.asRecentPlaylists(): List<MusicPlaylist> {
    val first = songs.firstOrNull()
    val colors = mainSourceArtworkColors("已播歌曲")
    val songsPlaylist = MusicPlaylist("${source.name.lowercase()}-recent:songs", source, "已播歌曲", "最近播放",
        "已自动同步你在其他设备播放的歌曲", songCount.coerceAtLeast(songs.size),
        first?.artworkStart ?: colors.first, first?.artworkEnd ?: colors.second, "播", songs, first?.artworkUrl,
        providerData = mapOf("templateEphemeral" to "true"))
    return listOf(songsPlaylist).filter { songs.isNotEmpty() } + playlists
}
