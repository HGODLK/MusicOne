package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 首页只调用既有公开播放操作；私人电台另走原 source-radio 入口。 */
internal class KugouHomeActions(private val owner: KugouPackageState, private val collections: SourceCollections) {
    private var collectionJob: Job? = null

    /** 货架点歌交给宿主，和公共首页一样按用户设置开猜你喜欢或插入当前队列。 */
    fun playRecommendation(track: SourceTrackDetails) = safely {
        requireCurrent(track.track.identity)
        owner.environment.host.playRecommendation(track)
    }

    fun playCollection(collection: SourceCollection) {
        collectionJob?.cancel()
        collectionJob = owner.scope.launch {
            try {
                val captured = owner.capture()
                requireCurrent(collection.identity)
                val detail = collections.detail(collection)
                val tracks = mutableListOf<SourceTrackDetails>()
                val cursors = mutableSetOf<String>()
                var cursor: String? = null
                do {
                    owner.check(captured)
                    val page = collections.tracks(detail, SourceCursorRequest(cursor, 100))
                    tracks += page.items
                    cursor = page.nextCursor
                    check(cursor == null || cursors.add(cursor)) { "歌单分页没有前进" }
                } while (cursor != null)
                owner.check(captured)
                require(tracks.isNotEmpty()) { "歌单暂时没有可播放的歌曲" }
                tracks.forEach { requireCurrent(it.track.identity) }
                owner.environment.host.playDetailed(tracks)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { owner.environment.host.reportError(failure.message ?: "歌单加载失败") }
        }
    }

    fun radio() = safely {
        requireNotNull(owner.environment.host.ui) { "宿主没有提供猜你喜欢入口" }
            .openDestination("source-radio", mapOf("sourceId" to KUGOU_SOURCE_ID))
    }

    private fun requireCurrent(identity: TrackIdentity) {
        require(identity.sourceId == KUGOU_SOURCE_ID && identity.accountId == owner.account()?.userId) {
            "酷狗账号已变化，请刷新首页"
        }
    }

    private inline fun safely(action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { owner.environment.host.reportError(failure.message ?: "操作失败") }
    }
}
