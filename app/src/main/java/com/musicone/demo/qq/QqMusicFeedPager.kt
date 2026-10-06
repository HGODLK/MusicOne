package com.musicone.demo

/**
 * 按官方货架游标顺序读取音乐流。
 * 服务端决定内容和游标，客户端只负责跨页去重、暂存卡位及本地卡池类型平衡。
 */
internal class QqMusicFeedPager(
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
        val seen = if (replace) hashSetOf() else displayed.mapTo(hashSetOf(), QqMusicFeedCard::key)
        var pool = pending.filter { it.key !in seen }.distinctBy(QqMusicFeedCard::key)
        var requests = 0
        while (pool.size < size && requests < QQ_FEED_PAGE_PROBE_LIMIT && !endReached) {
            val result = fetch(page + 1, shelfCount, shelfIds, uniqueKeys)
            requests++
            page++
            shelfIds = (shelfIds + result.shelfIds).distinct()
            // 官方的 s_num 是当前货架模型总数，不是去重后的 v_cache 数量。
            // 同一个 shelf ID 可以在一次响应中出现多个货架，不能用 distinct 后的数量替代。
            shelfCount += result.shelfCount
            uniqueKeys = (uniqueKeys + result.uniqueKeys).distinct().takeLast(QQ_FEED_UNIQUE_KEY_LIMIT)
            endReached = result.shelfCount == 0
            pool = mergeQqMusicFeed(pool, result.cards.filter { it.key !in seen })
        }

        if (pool.isEmpty()) {
            throw PlatformApiException(
                if (endReached) "QQ 音乐暂时没有更多推荐" else "暂未获得新的推荐内容，轻触继续加载",
            )
        }
        // 只在当前已取得的池内整理顺序，不为了寻找另一种卡型额外探测接口。
        val arrangedPool = interleaveQqMusicFeedCards(pool)
        val batch = takeQqMusicFeedBatch(arrangedPool, size)
        pending = batch.remaining
        return batch
    }
}

private const val QQ_FEED_PAGE_PROBE_LIMIT = 8
private const val QQ_FEED_UNIQUE_KEY_LIMIT = 100
