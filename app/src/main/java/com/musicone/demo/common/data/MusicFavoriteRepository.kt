package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface PlatformFavoriteAdapter {
    val source: MusicSource
    suspend fun likedTrackIds(session: PlatformSession): Set<String>
    suspend fun setLiked(session: PlatformSession, track: MusicTrack, liked: Boolean)

    suspend fun setLiked(session: PlatformSession, tracks: List<MusicTrack>, liked: Boolean) {
        tracks.forEach { track -> setLiked(session, track, liked) }
    }
}

internal class MusicFavoriteRepository(context: Context) {
    private val application = context.applicationContext
    private val preferences = PlatformPreferences(context)
    private val adapters = listOf<PlatformFavoriteAdapter>(
        NeteaseFavoriteAdapter(NeteaseApiClient()),
        QqFavoriteAdapter(QqApiClient()),
        KugouFavoriteAdapter(KugouApiClient()),
    ).associateBy(PlatformFavoriteAdapter::source)
    private val qqSyncCoordinator = QqFavoriteSyncCoordinator()

    suspend fun likedTrackIds(source: MusicSource): Set<String> = withContext(Dispatchers.IO) {
        val adapter = adapters[source] ?: return@withContext emptySet()
        val session = preferences.readSession(source)
        if (session.account == null || session.credential.isBlank()) return@withContext emptySet()
        adapter.likedTrackIds(session).also {
            MusicDiskCache.get(application).setFavorites(session.cacheNamespace(), it)
        }
    }

    suspend fun setLiked(track: MusicTrack, liked: Boolean) = setLiked(listOf(track), liked)

    suspend fun setLiked(tracks: List<MusicTrack>, liked: Boolean) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        val source = tracks.first().source
        if (tracks.any { it.source != source }) throw PlatformApiException("不能批量修改不同平台的收藏")
        val adapter = adapters[source]
            ?: throw PlatformApiException("${source.label}暂不支持收藏")
        val session = preferences.readSession(source)
        if (session.account == null || session.credential.isBlank()) {
            throw PlatformApiException("请先登录${source.label}")
        }
        val write = suspend { adapter.setLiked(session, tracks.distinctBy(MusicTrack::id), liked) }
        if (source == MusicSource.QQ) qqSyncCoordinator.runWrite(write) else write()
        MusicDiskCache.get(application).changeFavorites(session.cacheNamespace(), tracks.map { it.id }, liked)
    }
}

private class KugouFavoriteAdapter(
    private val api: KugouApiClient,
) : PlatformFavoriteAdapter {
    override val source = MusicSource.KUGOU

    override suspend fun likedTrackIds(session: PlatformSession): Set<String> = api.likedTrackIds(session.credential)

    override suspend fun setLiked(session: PlatformSession, track: MusicTrack, liked: Boolean) {
        api.setTrackLiked(session.credential, track, liked)
    }

}

private class QqFavoriteAdapter(
    private val api: QqApiClient,
) : PlatformFavoriteAdapter {
    override val source = MusicSource.QQ

    override suspend fun likedTrackIds(session: PlatformSession): Set<String> =
        api.likedTrackIds(session.credential, requireNotNull(session.account).userId)

    override suspend fun setLiked(session: PlatformSession, track: MusicTrack, liked: Boolean) {
        api.setTrackLiked(session.credential, track, liked)
    }

    override suspend fun setLiked(session: PlatformSession, tracks: List<MusicTrack>, liked: Boolean) {
        // 删除使用收藏记录的编号与类型，推荐入口的资料可能尚未经过详情补全。
        val resolved = if (liked) tracks else {
            val account = requireNotNull(session.account)
            val favorites = QqLibraryClient().favoritePlaylist(
                session.credential, account.userId, account.hasVipAccess,
            ).tracks.associateBy(MusicTrack::id)
            tracks.map { track -> favorites[track.id] ?: track }
        }
        api.setTracksLiked(session.credential, resolved, liked)
    }
}

private class NeteaseFavoriteAdapter(
    private val api: NeteaseApiClient,
) : PlatformFavoriteAdapter {
    override val source = MusicSource.NETEASE

    override suspend fun likedTrackIds(session: PlatformSession): Set<String> =
        api.likedTrackIds(session.credential, requireNotNull(session.account).userId)

    override suspend fun setLiked(session: PlatformSession, track: MusicTrack, liked: Boolean) {
        api.setTrackLiked(
            cookie = session.credential,
            userId = requireNotNull(session.account).userId,
            trackId = track.remoteId(),
            liked = liked,
        )
    }
}
