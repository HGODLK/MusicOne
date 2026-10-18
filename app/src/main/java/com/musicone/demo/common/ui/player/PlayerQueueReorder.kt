package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.first

/** 接管单行时先释放原生动画，再提交重排，避免两条动画时间线交接闪帧。 */
internal class PlayerQueueReorder(
    private val list: LazyListState,
    private val presentation: () -> PlayerQueuePresentation,
    private val delegate: PlaybackQueueActions,
) {
    private data class RowPosition(val top: Float, val height: Int)
    private val rows = mutableMapOf<String, RowPosition>()
    private var viewportTop = 0f
    private var viewportHeight by mutableIntStateOf(0)
    private var scroll by mutableFloatStateOf(0f)
    private var laidOutQueue by mutableStateOf<List<MusicTrack>?>(null, referentialEqualityPolicy())
    val exits = mutableStateListOf<PlayerQueueRowExit>()
    val scrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // 只累计列表实际消耗的距离，拖动与惯性同样直接跟随，不经过弹簧追赶。
            if (exits.isNotEmpty()) scroll += consumed.y
            return Offset.Zero
        }
    }
    val actions = PlaybackQueueActions(
        insertNext = { track -> if (!prepare(track)) delegate.insertNext(track) },
        remove = delegate.remove,
    )

    private fun prepare(track: MusicTrack): Boolean {
        if (owns(track.id)) return true
        val state = presentation()
        val source = state.queue.indexOfFirst { it.id == track.id }
        val current = state.queue.indexOfFirst { it.id == state.currentTrackId }
        if (source < 0 || current < 0 || source == current) return false
        val target = current + if (source < current) 0 else 1
        if (source == target) return false
        val layout = list.layoutInfo
        val visible = layout.visibleItemsInfo.filter {
            it.offset + it.size > 0 && it.offset < viewportHeight
        }
        val row = rows[track.id]
        val top = row?.top?.minus(viewportTop)
        val first = visible.minOfOrNull { it.index }
        val last = visible.maxOfOrNull { it.index }
        if (row != null && top != null && first != null && last != null &&
            top + row.height > 0 && top < viewportHeight && (target < first || target > last)) {
            exits += PlayerQueueRowExit(track, source, top, row.height, target < first, scroll)
            return true
        }
        retainViewport()
        return false
    }

    fun commit(exit: PlayerQueueRowExit) {
        if (exit !in exits || !exit.sourceReleased || exit.committed) return
        // onGloballyPositioned 已确认源行不带 animateItem 完成布局，旧原生图层已释放。
        val state = presentation()
        val source = state.queue.indexOfFirst { it.id == exit.track.id }
        val current = state.queue.indexOfFirst { it.id == state.currentTrackId }
        val target = current + if (source < current) 0 else 1
        if (source < 0 || current < 0 || source == current || source == target) {
            exits.remove(exit)
            delegate.insertNext(exit.track)
            return
        }
        exit.originalQueue = state.queue
        retainViewport()
        delegate.insertNext(exit.track)
        if (presentation().queue === exit.originalQueue) {
            exits.remove(exit)
            return
        }
        exit.committed = true
    }

    private fun retainViewport() {
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }

    fun owns(id: String): Boolean = exits.any { it.track.id == id }
    fun scrollShift(exit: PlayerQueueRowExit): Float = scroll - exit.scrollAtStart

    fun destination(exit: PlayerQueueRowExit): Float {
        val layout = list.layoutInfo
        val queue = presentation().queue
        val targetIndex = queue.indexOfFirst { it.id == exit.track.id }
        val natural = if (exit.committed && laidOutQueue === queue) layout.visibleItemsInfo.firstOrNull {
            it.key == exit.track.id && it.index == targetIndex &&
                it.offset + it.size > 0 && it.offset < viewportHeight
        }?.offset?.toFloat() else null
        val upward = if (targetIndex >= 0 && exit.committed) targetIndex < list.firstVisibleItemIndex else exit.upward
        return queueReorderDestination(natural, upward, viewportHeight, exit.height, scrollShift(exit))
    }

    fun layoutReady(exit: PlayerQueueRowExit): Boolean =
        exit.committed && laidOutQueue === presentation().queue && laidOutQueue !== exit.originalQueue

    fun layoutQueue(queue: List<MusicTrack>) { laidOutQueue = queue }

    fun finish(exit: PlayerQueueRowExit, contentTop: Float): Boolean {
        if (exit !in exits || !layoutReady(exit)) return false
        val top = contentTop + scrollShift(exit)
        val destination = destination(exit) + scrollShift(exit)
        // 整行离开窗口立即撤层，真实行已在目标处；屏内仍须连续飞到真实位置。
        if (!queueRowOutsideViewport(top, exit.height, viewportHeight) &&
            kotlin.math.abs(top - destination) > .1f) return false
        exits.remove(exit)
        return true
    }

    fun index(exit: PlayerQueueRowExit): Int = presentation().queue.indexOfFirst { it.id == exit.track.id }
        .takeIf { it >= 0 } ?: exit.index

    fun positionRow(id: String, top: Float, height: Int, owned: Boolean) {
        rows[id] = RowPosition(top, height)
        if (owned) exits.firstOrNull { it.track.id == id && !it.committed }?.sourceReleased = true
    }

    fun forgetRow(id: String) {
        rows.remove(id)
        // 未重排前源行因用户滚动离开，副本可以直接撤销，插入动作仍须执行。
        exits.firstOrNull { it.track.id == id && !it.committed }?.let {
            exits.remove(it)
            delegate.insertNext(it.track)
        }
    }
    fun positionViewport(top: Float, height: Int) { viewportTop = top; viewportHeight = height }
}

internal class PlayerQueueRowExit(
    val track: MusicTrack,
    val index: Int,
    val from: Float,
    val height: Int,
    val upward: Boolean,
    val scrollAtStart: Float,
) {
    var originalQueue: List<MusicTrack>? = null
    var sourceReleased by mutableStateOf(false)
    var committed by mutableStateOf(false)
}

/** 目标自然行进入窗口后使用真实位置；列表滚动单独应用，不混入弹簧当前位置。 */
internal fun queueReorderDestination(naturalTop: Float?, upward: Boolean, viewportHeight: Int,
    rowHeight: Int, scrollShift: Float): Float =
    (naturalTop ?: if (upward) -rowHeight * 2f else viewportHeight + rowHeight.toFloat()) - scrollShift

@Composable
internal fun rememberPlayerQueueReorder(list: LazyListState, player: MusicPlaybackRuntime): PlayerQueueReorder {
    val actions = rememberPlaybackQueueActions(player)
    return remember(list, player, actions) {
        PlayerQueueReorder(list, {
            val state = player.state.value
            PlayerQueuePresentation(state.queue, state.currentTrack?.id, state.isPlaying)
        }, actions)
    }
}

@Composable
internal fun Modifier.queueReorderRow(reorder: PlayerQueueReorder, id: String, owned: Boolean): Modifier {
    DisposableEffect(reorder, id) { onDispose { reorder.forgetRow(id) } }
    return onGloballyPositioned {
        reorder.positionRow(id, it.positionInRoot().y, it.size.height, owned)
    }.graphicsLayer { alpha = if (reorder.owns(id)) 0f else 1f }
}

@Composable
internal fun PlayerQueueReorderOverlay(reorder: PlayerQueueReorder) {
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        reorder.exits.forEach { exit ->
            key(exit) {
                val flight = remember(exit) { PlayerQueueFlight(exit.from) }
                LaunchedEffect(exit) {
                    snapshotFlow { exit.sourceReleased }.first { it }
                    reorder.commit(exit)
                    snapshotFlow { reorder.layoutReady(exit) }.first { it }
                    var previous = withFrameNanos { it }
                    while (reorder.owns(exit.track.id)) {
                        val now = withFrameNanos { it }
                        val seconds = (now - previous) / 1_000_000_000f
                        previous = now
                        if (!reorder.layoutReady(exit)) continue
                        flight.advance(reorder.destination(exit), seconds, ExperiencePreferences.options.reduceMotion)
                        if (reorder.finish(exit, flight.position)) break
                    }
                }
                Box(Modifier.fillMaxWidth().graphicsLayer {
                    translationY = flight.position + reorder.scrollShift(exit)
                }) {
                    QueueRow(reorder.index(exit), exit.track, current = false, interactive = false)
                }
            }
        }
    }
}
