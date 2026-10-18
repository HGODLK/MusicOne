package com.musicone.demo

/** 歌单写入前核验当前账号的自建目录，页面缓存中的归属标记只用于展示。 */
internal class PlatformPlaylistSongRepository {
    private val netease = NeteaseApiClient()
    private val kugou = KugouSessionClient()

    fun targets(session: PlatformSession): List<MusicPlaylist> {
        val account = session.account ?: throw PlatformApiException("请先登录对应音乐平台", 301)
        return when (account.source) {
            MusicSource.NETEASE -> netease.userPlaylists(session.credential, account.userId)
                .filter { it.isOwned && !it.isPlatformFavorites }
            MusicSource.KUGOU -> kugou.library(session.credential, loadFavoriteTracks = false).created
            else -> emptyList()
        }
    }

    fun target(session: PlatformSession, playlistId: String): MusicPlaylist =
        targets(session).firstOrNull { it.id == playlistId }
            ?: throw PlatformApiException("只能修改当前账号创建的歌单，请刷新后重试")

    fun change(session: PlatformSession, target: MusicPlaylist, track: MusicTrack, add: Boolean) {
        require(track.source == target.source) { "此歌单只接受同平台歌曲" }
        when (target.source) {
            MusicSource.NETEASE -> netease.changePlaylistTrack(session.credential, target.remoteId(), track.remoteId(), add)
            MusicSource.KUGOU -> kugou.changePlaylistTrack(session.credential, target, track, add)
            else -> throw PlatformApiException("当前平台不支持在线歌单编辑")
        }
    }
}

internal fun MusicPlaylist.isAccountFavorites(): Boolean = when (source) {
    MusicSource.QQ -> isQqFavoritesShortcut()
    MusicSource.NETEASE, MusicSource.KUGOU -> isOwned && isPlatformFavorites
    else -> false
}

internal val MusicPlaylist.supportsSongMenu: Boolean
    get() = isManagedLibrary || source in setOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU)
