package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * B站收藏夹全量订阅同步助手。
 *
 * 只遍历所选收藏夹的列表分页；视频分 P 和音频地址延迟到实际点击时读取。
 */
internal class BiliSubscriptionSyncer(
    private val api: BiliApi,
    private val libraryStore: MusicLibraryStore,
) {
    /**
     * 同步所选收藏夹的视频列表，保留已经导入的分 P。
     *
     * @param folder 目标收藏夹
     * @param targetPlaylistId 本地歌单 ID
     * @param account 登录会话
     * @param isCancelled 代次取消校验函数
     */
    suspend fun syncFolder(
        folder: BiliFavFolder,
        targetPlaylistId: String,
        account: BiliAccountSession,
        isCancelled: () -> Boolean,
        onCommitted: () -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()
        require(targetPlaylistId != UNIFIED_PLAYLIST_ID && targetPlaylistId != ALL_LOCAL_PLAYLIST_ID) {
            "禁止综合歌单被订阅，请为订阅创建独立本地歌单"
        }
        val allTracks = mutableListOf<MusicTrack>()
        val originalSubscription = libraryStore.state.value.subscriptions.firstOrNull { it.playlistId == targetPlaylistId }
        val previousTracks = libraryStore.subscriptionTracks(targetPlaylistId)
        var page = 1
        var hasMore = true

        while (hasMore) {
            if (isCancelled()) throw CancellationException()
            // getFavoriteVideos 失败直接抛出异常，中断流程保旧，不触发 merge
            val resp = api.getFavoriteVideos(folder.id, page, account.cookie)
            for (video in resp.videos) {
                if (isCancelled()) throw CancellationException()
                allTracks += biliSubscriptionTracks(video, previousTracks)
            }
            hasMore = resp.hasMore
            require(!hasMore || resp.videos.isNotEmpty()) { "收藏夹分页信息不完整，已保留原订阅内容" }
            page++
        }

        if (isCancelled()) throw CancellationException()
        if (libraryStore.state.value.subscriptions.firstOrNull { it.playlistId == targetPlaylistId } != originalSubscription) throw CancellationException()

        // 仅在列表完整分页成功后才执行 merge；保留现有 subscription.manualOrder。
        val existingSub = libraryStore.state.value.subscriptions.firstOrNull { it.playlistId == targetPlaylistId }
        val manualOrder = existingSub?.manualOrder ?: false

        val subscription = LibrarySubscription(
            playlistId = targetPlaylistId,
            folderId = folder.id,
            ownerId = account.mid,
            manualOrder = manualOrder,
        )
        libraryStore.mergeSubscription(subscription, allTracks, originalSubscription, isCancelled = {
            operation.ensureActive()
            isCancelled()
        }, onCommitted = onCommitted)
        allTracks.size
    }

    /**
     * 根据歌单 ID 同步该歌单关联的 B站收藏夹订阅，供外部或主代理打开歌单时调用。
     */
    suspend fun syncSubscription(
        playlistId: String,
        account: BiliAccountSession,
        isCancelled: () -> Boolean = { false },
    ): Int {
        require(playlistId != UNIFIED_PLAYLIST_ID && playlistId != ALL_LOCAL_PLAYLIST_ID) {
            "禁止综合歌单或全部本地歌单被订阅"
        }
        val sub = libraryStore.state.value.subscriptions.firstOrNull { it.playlistId == playlistId }
            ?: throw PlatformApiException("未找到歌单 $playlistId 的订阅信息")
        require(sub.ownerId == account.mid) { "请登录订阅所属的B站账号" }
        val folder = BiliFavFolder(id = sub.folderId, title = "", mediaCount = 0)
        return syncFolder(folder, playlistId, account, isCancelled)
    }
}
