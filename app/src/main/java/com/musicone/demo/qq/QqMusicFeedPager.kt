package com.musicone.demo

/**
 * 按官方货架游标顺序读取音乐流。
 * 服务端决定内容和游标，客户端只负责跨页去重、暂存卡位及本地卡池类型平衡。
 */
internal class QqMusicFeedPager(
    private val prepare: suspend (List<QqMusicFeedCard>) -> List<QqMusicFeedCard> = { it },
    private val fetch: suspend (Int, Int, List<String>, List<String>) -> QqMusicFeedPage,
) {
    private var page = 0
    private var shelfCount = 0
    private var shelfIds = emptyList<String>()
    private var uniqueKeys = emptyList<String>()
    private var pending = emptyList<QqMusicFeedCard>()
    private var endReached = false

    suspend fun next(displayed: List<QqMusicFeedCard>, size: Int, replace: Boolean): QqMusicFeedBatch {
        if (size <= 0) return QqMusicFeedBatch(emptyList(), pending)
        if (replace) {
            page = 0
            shelfCount = 0
            shelfIds = emptyList()
            uniqueKeys = emptyList()
            pending = emptyList()
            endReached = false
        }

        // 刷新代表官方清空旧列表后重新接收第一页；不能把旧页面的 key 当成刷新排重集，
        // 否则服务端正常返回相同推荐时会被客户端误删，最终只剩后续少量歌单。
        val previous = if (replace) emptyList() else displayed
        var pool = normalizeQqMusicFlowCards(unseenQqMusicFeedCards(pending, previous))
        val firstBatch = replace || displayed.isEmpty()
        var requests = 0
        val probeLimit = if (firstBatch) QQ_FEED_INITIAL_PROBE_LIMIT else QQ_FEED_PAGE_PROBE_LIMIT
        while (requests < probeLimit && !endReached) {
            val officialCount = pool.count { it is QqMusicFeedCard.SongShelf && it.section == QqMusicFeedSection.SONG_RECOMMENDATION }
            if (pool.isNotEmpty() && (!firstBatch || officialCount == 0 ||
                    officialCount >= minOf(size, QQ_OFFICIAL_RECOMMENDATION_LIMIT))) break
            val result = fetch(page + 1, shelfCount, shelfIds, uniqueKeys)
            requests++
            page++
            shelfIds = (shelfIds + result.shelfIds).distinct()
            // 官方的 s_num 是当前货架模型总数，不是去重后的 v_cache 数量。
            // 同一个 shelf ID 可以在一次响应中出现多个货架，不能用 distinct 后的数量替代。
            shelfCount += result.shelfCount
            uniqueKeys = (uniqueKeys + result.uniqueKeys).distinct().takeLast(QQ_FEED_UNIQUE_KEY_LIMIT)
            endReached = result.shelfCount == 0
            val incoming = unseenQqMusicFeedCards(result.cards, previous + pool)
            pool = mergeQqMusicFeed(pool, incoming)
            // 原始响应先留在池中；准备失败重试时复用已取得的数据，不丢失成功页。
            pending = pool
        }

        if (pool.isEmpty()) {
            throw PlatformApiException(
                if (endReached) "QQ 音乐暂时没有更多推荐" else "暂未获得新的推荐内容，轻触继续加载",
            )
        }
        // 只在当前已取得的池内整理顺序，不为了寻找另一种卡型额外探测接口。
        val arrangedPool = qqMusicFeedDisplayCards(interleaveQqMusicFeedCards(pool))
        val candidates = if (firstBatch) qqFeedInitialCandidates(arrangedPool) else arrangedPool
        val selected = takeQqMusicFeedBatch(candidates, size).visible
        val visible = prepare(selected)
        val selectedKeys = selected.mapTo(hashSetOf(), QqMusicFeedCard::key)
        pending = arrangedPool.filterNot { it.key in selectedKeys }
        if (visible.isEmpty()) throw PlatformApiException("暂未获得可展示的推荐内容，轻触继续加载")
        return QqMusicFeedBatch(visible, pending)
    }
}

private const val QQ_FEED_PAGE_PROBE_LIMIT = 8
private const val QQ_FEED_INITIAL_PROBE_LIMIT = 3
private const val QQ_FEED_UNIQUE_KEY_LIMIT = 100
