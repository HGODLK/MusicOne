package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceCollectionOperation
import com.musicone.sourceintegration.*

/** 歌单写入归属和远端协议由当前音源包核验。 */
internal class PlatformPlaylistSongRepository(context: Context) {
    private val controller = SourcePackageController.get(context)
    private fun entry(source: MusicSource) = controller.running(source.id)?.entry ?: error("${source.label}音源未启用")
    suspend fun targets(session: PlatformSession): List<MusicPlaylist> {
        val service = entry(session.source)
        requireNotNull(service.authentication?.currentAccount()?.account) { "请先登录对应音乐平台" }
        val editing = requireNotNull(service.collectionEditing) { "此音源不支持在线歌单编辑" }
        if (SourceCollectionOperation.CHANGE_TRACKS !in editing.supportedOperations) return emptyList()
        return editing.writableCollections().map { it.toMusicPlaylist() }
    }
    suspend fun target(session: PlatformSession, playlistId: String): MusicPlaylist =
        targets(session).firstOrNull { it.id == playlistId } ?: error("只能修改当前账号创建的歌单，请刷新后重试")
    suspend fun change(session: PlatformSession, target: MusicPlaylist, track: MusicTrack, add: Boolean) =
        changeBatch(session, target, listOf(track), add)
    suspend fun changeBatch(session: PlatformSession, target: MusicPlaylist, tracks: List<MusicTrack>, add: Boolean) {
        require(tracks.all { it.source == target.source }) { "此歌单只接受同音源歌曲" }
        val service = entry(target.source)
        val owner = requireNotNull(service.authentication?.currentAccount()?.account?.id) { "请先登录对应音乐平台" }
        requireNotNull(service.collectionEditing) { "此音源不支持在线歌单编辑" }
            .changeTracks(target.toSourceCollection(owner), tracks.map { it.toSourceTrack(owner) }, add)
        check(service.authentication?.currentAccount()?.account?.id == owner) { "账号已变化，旧写入结果已取消" }
    }
}

internal fun MusicPlaylist.isAccountFavorites(): Boolean = isPlatformFavorites
internal fun MusicPlaylist.canCollectAsPlaylist(): Boolean =
    !isAccountFavorites() && providerData["templateEphemeral"] != "true"
internal val MusicPlaylist.supportsSongMenu: Boolean
    get() = isManagedLibrary || source != MusicSource.LOCAL
