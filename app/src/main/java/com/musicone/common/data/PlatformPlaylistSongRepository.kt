package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceCollectionMembership
import com.musicone.source.sdk.SourceCollectionOperation
import com.musicone.source.sdk.SourceFetchPolicy
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
    /** “添加到歌单”用：未登录或不支持在线歌单编辑的音源没有可添加的歌单，直接返回空，不算读取失败。 */
    suspend fun additionTargets(session: PlatformSession): List<MusicPlaylist> {
        val service = entry(session.source)
        if (service.authentication?.currentAccount()?.account == null || service.collectionEditing == null) return emptyList()
        return targets(session)
    }
    /** 音源本地已知的可写歌单，不联网；音源未提供或没有缓存时返回 null。 */
    suspend fun cachedTargets(session: PlatformSession): List<MusicPlaylist>? {
        val service = controller.running(session.source.id)?.entry ?: return null
        if (service.authentication?.currentAccount()?.account == null) return null
        val editing = service.collectionEditing ?: return null
        if (SourceCollectionOperation.CHANGE_TRACKS !in editing.supportedOperations) return emptyList()
        val membership = (editing as? SourceCollectionMembership)?.takeIf { it.supportsMembership } ?: return null
        return membership.cachedWritableCollections()?.map { it.toMusicPlaylist() }
    }
    /** 音源给出的歌单归属，返回已在歌单内的宿主曲目 ID；音源未实现或缓存没有索引时返回 null。 */
    suspend fun containedTracks(target: MusicPlaylist, tracks: List<MusicTrack>, policy: SourceFetchPolicy): Set<String>? {
        val service = controller.running(target.source.id)?.entry ?: return null
        val owner = service.authentication?.currentAccount()?.account?.id ?: return null
        val membership = (service.collectionEditing as? SourceCollectionMembership)?.takeIf { it.supportsMembership } ?: return null
        val details = tracks.map { it.toSourceTrack(owner) }
        val hostIds = details.zip(tracks).associate { (detail, track) -> detail.stableId to track.id }
        val contained = membership.containedTracks(target.toSourceCollection(owner), details, policy) ?: return null
        check(service.authentication?.currentAccount()?.account?.id == owner) { "账号已变化，旧歌单归属已取消" }
        return contained.mapNotNullTo(hashSetOf()) { hostIds[it] }
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
