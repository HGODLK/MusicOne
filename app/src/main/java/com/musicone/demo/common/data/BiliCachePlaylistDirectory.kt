package com.musicone.demo

import android.content.Context

/** 缓存管理复用收藏夹目录快照与分页仓库，不把收藏夹转换为本地订阅。 */
internal class BiliCachePlaylistDirectory(context: Context) {
    private val sessions = BiliSessionStore(context)
    private val cache = BiliFolderCache(context)
    private val api = BiliApi(context)
    private val contents = BiliFolderRepository(cache) { account, folder, page, query ->
        api.getFavoriteVideos(folder, page, account.cookie, query)
    }

    fun cached(): List<MusicPlaylist> {
        val account = sessions.read() ?: return emptyList()
        return cache.folders(account.mid).map { folder ->
            val snapshot = cache.content(account.mid, folder.id)
            biliCachePlaylist(folder, snapshot)
        }
    }

    suspend fun remote(): List<MusicPlaylist> {
        val account = sessions.read() ?: return emptyList()
        val known = cache.folders(account.mid).associateBy { it.id }
        val folders = api.getFavoriteFolders(account.mid.toLong(), account.cookie).map {
            if (it.cover.isBlank()) it.copy(cover = known[it.id]?.cover.orEmpty()) else it
        }
        check(sessions.read() == account) { "B站账号已变化，请重新打开缓存管理" }
        val playlists = folders.map { folder ->
            val snapshot = contents.first(account, contents.entry(account.mid, folder.id), refresh = true)
            biliCachePlaylist(folder, snapshot).let { it.copy(count = maxOf(folder.mediaCount, it.count)) }
        }
        check(sessions.read() == account) { "B站账号已变化，请重新打开缓存管理" }
        cache.saveFolders(account.mid, folders.map { folder ->
            folder.copy(cover = playlists.firstOrNull { it.id == "bili-folder-${folder.id}" }?.artworkUrl ?: folder.cover)
        })
        return playlists
    }

    suspend fun load(playlist: MusicPlaylist): MusicPlaylist {
        val account = sessions.read() ?: error("请先登录哔哩哔哩")
        val folderId = playlist.id.removePrefix("bili-folder-").toLongOrNull() ?: error("收藏夹编号无效")
        val entry = contents.entry(account.mid, folderId)
        contents.first(account, entry, refresh = true)
        val snapshot = contents.complete(account, entry)
        check(sessions.read() == account) { "B站账号已变化，请重新打开收藏夹" }
        val cover = playlist.artworkUrl?.takeIf(String::isNotBlank)
            ?: snapshot.videos.firstOrNull { it.cover.isNotBlank() }?.cover
        return biliCachePlaylist(BiliFavFolder(folderId, playlist.title, playlist.count, cover.orEmpty()), snapshot)
    }
}

internal fun biliCachePlaylist(folder: BiliFavFolder, snapshot: BiliFolderSnapshot?): MusicPlaylist {
    val tracks = snapshot?.videos.orEmpty().map(BiliTrackMapper::toFallbackTrack)
    val count = when {
        snapshot == null -> folder.mediaCount
        !snapshot.hasMore -> tracks.size
        else -> maxOf(folder.mediaCount, tracks.size + 1)
    }
    return folder.asPlaylist(tracks).copy(count = count, artworkUrl = latestBiliFolderCover(snapshot?.videos.orEmpty())
        ?: folder.cover.takeIf(String::isNotBlank))
}

/** 收藏接口已按收藏时间倒序返回；封面跟随最新有效视频，不使用服务端固定旧封面。 */
internal fun latestBiliFolderCover(videos: List<BiliVideoItem>): String? =
    videos.firstOrNull { !it.isInvalid && it.bvid.isNotBlank() && it.cover.isNotBlank() }?.cover
