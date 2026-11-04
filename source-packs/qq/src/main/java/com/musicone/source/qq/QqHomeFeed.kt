package com.musicone.source.qq

import com.musicone.source.qq.internal.*
import com.musicone.source.sdk.SourceCursorRequest
import com.musicone.source.sdk.SourceOperationException
import com.musicone.source.sdk.SourcePage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 包内保留官方游标和延迟准备卡池；宿主仅查询当前游标是否还有已取得的内容。 */
internal class QqHomeFeed(private val newPager: () -> QqMusicFeedPager) {
    private data class State(
        val credential: String,
        val pager: QqMusicFeedPager,
        var displayed: List<QqMusicFeedCard> = emptyList(),
        var sequence: Int = 0,
        var buffered: Boolean = false,
    )
    private val states = ConcurrentHashMap<String, State>()

    suspend fun next(request: SourceCursorRequest, credential: String): SourcePage<QqMusicFeedCard> {
        val first = request.cursor == null
        val token = request.cursor?.substringBefore(':') ?: UUID.randomUUID().toString()
        val state = if (first) State(credential, newPager()).also { states[token] = it }
            else states[token] ?: throw SourceOperationException("home-cursor-expired", "推荐分页已失效，请刷新", true)
        check(state.credential == credential) { "推荐所属账号已变化，请刷新" }
        check(first || request.cursor?.substringAfter(':')?.toIntOrNull() == state.sequence) { "推荐分页已变化，请刷新" }
        val batch = state.pager.next(state.displayed, request.limit.coerceAtLeast(1), first)
        state.displayed = appendQqMusicFeedDisplayCards(state.displayed, batch.visible, first)
        state.buffered = batch.remaining.isNotEmpty()
        state.sequence++
        val cursor = if (state.pager.finished || batch.visible.isEmpty()) {
            states.remove(token)
            null
        } else "$token:${state.sequence}"
        return SourcePage(batch.visible, cursor)
    }

    fun hasBufferedItems(cursor: String): Boolean {
        val state = states[cursor.substringBefore(':')] ?: return false
        return cursor.substringAfter(':').toIntOrNull() == state.sequence && state.buffered
    }

    fun clear() = states.clear()
}
