package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 拖动位置使用网格视口坐标，重排和自动滚动后仍由同一手指连续接管。 */
@Stable
internal class PlaylistCardReorderState(
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
    initial: List<String>,
    sourceIds: List<String>,
    private val local: Boolean,
    private val edge: Float,
    private val save: suspend (List<String>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    var order by mutableStateOf(reconcilePlaylistCardOrder(initial, sourceIds))
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var center by mutableStateOf(Offset.Zero)
    private val settle = Animatable(1f)
    private var source = sourceIds
    private var saved = initial
    private var before = order
    private var distance = Offset.Zero
    var moved by mutableStateOf(false)
        private set
    private var awaitingIndex: Int? = null
    private var scrollJob: Job? = null
    private var finishJob: Job? = null
    private var saving = false

    fun sync(ids: List<String>) {
        if (ids == source) return
        if (activeId != null && activeId !in ids) cancel()
        order = when {
            dragging || saving -> reconcilePlaylistCardOrder(order, ids)
            local -> ids
            else -> reconcilePlaylistCardOrder(saved, ids)
        }
        source = ids
    }

    fun begin(id: String): Boolean {
        if (activeId != null || saving) return false
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return false
        before = order
        activeId = id
        dragging = true
        center = Offset(item.offset.x + item.size.width / 2f, item.offset.y + item.size.height / 2f)
        distance = Offset.Zero
        moved = false
        awaitingIndex = null
        scrollJob = scope.launch {
            var previous = withFrameNanos { it }
            while (dragging) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
                if (!moved) continue
                val layout = grid.layoutInfo
                val speed = when {
                    center.y < layout.viewportStartOffset + edge -> -((layout.viewportStartOffset + edge - center.y) / edge).coerceIn(0f, 1f)
                    center.y > layout.viewportEndOffset - edge -> ((center.y - layout.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f) {
                    grid.scrollBy(speed * edge * 10f * seconds)
                    move(Offset.Zero)
                }
            }
        }
        return true
    }

    fun move(delta: Offset) {
        if (!dragging) return
        center += delta
        distance += delta
        if (distance.getDistance() > edge / 9f) moved = true
        if (!moved) return
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeId } ?: return
        if (awaitingIndex != null && item.index != awaitingIndex) return
        awaitingIndex = null
        val layout = grid.layoutInfo
        val candidates = layout.visibleItemsInfo.filter { (it.key as? String) in order }
        val target = candidates.firstOrNull {
            center.x >= it.offset.x && center.x < it.offset.x + it.size.width &&
                center.y >= it.offset.y && center.y < it.offset.y + it.size.height
        } ?: candidates.takeIf { center.y < layout.viewportStartOffset + edge || center.y > layout.viewportEndOffset - edge }
            ?.minByOrNull { (center - Offset(it.offset.x + it.size.width / 2f, it.offset.y + it.size.height / 2f)).getDistanceSquared() }
            ?: return
        if (target.key == activeId) return
        val from = order.indexOf(activeId ?: return)
        val to = order.indexOf(target.key as? String ?: return)
        if (from < 0 || to < 0 || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        awaitingIndex = target.index
    }

    fun translation(id: String): Offset {
        if (activeId != id) return Offset.Zero
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return Offset.Zero
        return (center - Offset(item.offset.x + item.size.width / 2f, item.offset.y + item.size.height / 2f)) * settle.value
    }

    fun finish(onStationary: () -> Unit) {
        if (!dragging) return
        dragging = false
        scrollJob?.cancel()
        if (!moved) {
            activeId = null
            onStationary()
            return
        }
        finishJob = scope.launch {
            val persistence = launch { persist(before) }
            withFrameNanos { }
            withFrameNanos { }
            settle.animateTo(0f, musicSpring())
            activeId = null
            settle.snapTo(1f)
            persistence.join()
        }
    }

    fun moveBy(id: String, delta: Int): Boolean {
        if (activeId != null || saving) return false
        val from = order.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in order.indices) return false
        val previous = order
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        finishJob = scope.launch { persist(previous) }
        return true
    }

    private suspend fun persist(previous: List<String>) {
        saving = true
        try { if (order != previous) save(order); saved = order }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { order = reconcilePlaylistCardOrder(previous, source); onFailure(error) }
        finally { saving = false }
    }

    fun cancel() {
        scrollJob?.cancel()
        finishJob?.cancel()
        if (dragging) order = reconcilePlaylistCardOrder(before, source)
        activeId = null
        dragging = false
        awaitingIndex = null
        scope.launch { settle.snapTo(1f) }
    }
}
