package com.musicone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/** 列表迁移时使旧滚动与预取句柄同步失效，正常挂载期间仍使用原预取策略。 */
@OptIn(ExperimentalFoundationApi::class)
internal class LyricListHost {
    var revision by mutableIntStateOf(0)
        private set
    private var coordinates: LayoutCoordinates? = null
    private var placed by mutableStateOf(false)
    val ready get() = placed && coordinates?.isAttached == true
    fun coordinates(): LayoutCoordinates? = coordinates.takeIf { ready }
    private var strategy = LazyListPrefetchStrategy()
    private val handles = mutableMapOf<Int, LazyLayoutPrefetchState.PrefetchHandle>()

    fun invalidate(cancelPrefetch: Boolean = true) {
        // 转向开始时旧树尚未拆卸，先清理预取；脱离后的原生句柄交由布局自身回收。
        if (cancelPrefetch && ready) handles.values.toList().forEach { it.cancel() }
        handles.clear()
        placed = false
        coordinates = null
        revision++
        strategy = LazyListPrefetchStrategy()
    }

    fun place(value: LayoutCoordinates) {
        coordinates = value
        if (!placed) { placed = true; revision++ }
    }

    fun scrollLease(): () -> Boolean {
        val expected = revision
        return { ready && revision == expected }
    }

    val prefetch = object : LazyListPrefetchStrategy {
        private fun LazyListPrefetchScope.ownedScope(): LazyListPrefetchScope {
            val parent = this
            return object : LazyListPrefetchScope {
                override fun schedulePrefetch(index: Int,
                    onPrefetchFinished: (LazyListPrefetchResultScope.() -> Unit)?): LazyLayoutPrefetchState.PrefetchHandle {
                    return parent.schedulePrefetch(index, onPrefetchFinished).also { handles[index] = it }
                }
            }
        }
        override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
            if (ready) with(strategy) { ownedScope().onScroll(delta, layoutInfo) }
        }
        override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
            if (!ready) return
            with(strategy) { ownedScope().onVisibleItemsUpdated(layoutInfo) }
            val first = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return
            val last = layoutInfo.visibleItemsInfo.last().index
            handles.keys.filter { it != first - 1 && it != last + 1 }.forEach { index ->
                handles.remove(index)?.cancel()
            }
        }
        override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
            if (ready) with(strategy) { onNestedPrefetch(firstVisibleItemIndex) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun newLyricWindowPane(id: Long, initialLine: Int, requestId: Long = 0L): LyricWindowPane {
    val host = LyricListHost()
    return LyricWindowPane(id, initialLine,
        LazyListState(firstVisibleItemIndex = initialLine, prefetchStrategy = host.prefetch),
        LyricPlaybackStepMotion(initialLine), requestId = requestId, listHost = host)
}

internal fun Modifier.lyricListHost(pane: LyricWindowPane, turn: PhonePlayerTurn?): Modifier =
    then(LyricListHostElement(pane, turn))

private data class LyricListHostElement(val pane: LyricWindowPane, val turn: PhonePlayerTurn?) :
    ModifierNodeElement<LyricListHostNode>() {
    override fun create() = LyricListHostNode(pane, turn)
    override fun update(node: LyricListHostNode) { node.update(pane, turn) }
    override fun InspectorInfo.inspectableProperties() { name = "lyricListHost" }
}

private class LyricListHostNode(var pane: LyricWindowPane, var turn: PhonePlayerTurn?) :
    Modifier.Node(), LayoutAwareModifierNode, GlobalPositionAwareModifierNode {
    private fun suspendHost() { pane.listHost.invalidate(); pane.playbackStep.holdHostPosition(true) }
    override fun onAttach() { turn?.beforeLayoutChange(this, ::suspendHost) }
    override fun onDetach() {
        turn?.removeLayoutChangeListener(this)
        pane.listHost.invalidate(cancelPrefetch = false)
        pane.playbackStep.holdHostPosition(true)
    }
    override fun onPlaced(coordinates: LayoutCoordinates) {
        pane.listHost.place(coordinates)
        pane.playbackStep.holdHostPosition(false)
    }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // 同尺寸的正反横屏也会更换空间方向，固定宿主不能停留在旧的暂停资格。
        pane.listHost.place(coordinates)
        pane.playbackStep.holdHostPosition(false)
    }
    fun update(next: LyricWindowPane, nextTurn: PhonePlayerTurn?) {
        if (pane === next && turn === nextTurn) return
        if (isAttached) onDetach()
        pane = next; turn = nextTurn
        if (isAttached) onAttach()
    }
}
