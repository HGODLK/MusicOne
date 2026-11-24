package com.musicone.sourceintegration

import com.musicone.source.sdk.TrackIdentity

/**
 * 主音源播放页承载所有音源的歌曲：宿主亲手交给播放页的歌曲，允许用该主音源的公共组件显示。
 * 保留最近几首，切歌过渡期间包仍在绘制上一首时也不会被判为越权；交给包的队列快照同样放行。
 * 其他场景仍只能显示本音源内容。
 */
internal object SourcePlayerDisplayedTracks {
    private const val RECENT_LIMIT = 8
    private class Entry(val sourceId: String, val identities: ArrayDeque<TrackIdentity> = ArrayDeque(),
        var queue: Set<TrackIdentity> = emptySet())
    private val pages = HashMap<Any, Entry>()

    /** 播放页每次把当前歌曲交给包之前登记，按播放页实例区分，换形态或重载不会互相清掉。 */
    @Synchronized fun record(page: Any, sourceId: String, identity: TrackIdentity) {
        val entry = pages[page]?.takeIf { it.sourceId == sourceId } ?: Entry(sourceId).also { pages[page] = it }
        if (entry.identities.firstOrNull() == identity) return
        entry.identities.remove(identity)
        entry.identities.addFirst(identity)
        while (entry.identities.size > RECENT_LIMIT) entry.identities.removeLast()
    }

    /** 队列快照整体替换，不累积已移出队列的歌曲。 */
    @Synchronized fun recordQueue(page: Any, sourceId: String, identities: Set<TrackIdentity>) {
        val entry = pages[page]?.takeIf { it.sourceId == sourceId } ?: Entry(sourceId).also { pages[page] = it }
        entry.queue = identities
    }

    @Synchronized fun release(page: Any) { pages.remove(page) }

    @Synchronized fun allows(sourceId: String, identity: TrackIdentity): Boolean =
        pages.values.any { it.sourceId == sourceId && (identity in it.identities || identity in it.queue) }
}
