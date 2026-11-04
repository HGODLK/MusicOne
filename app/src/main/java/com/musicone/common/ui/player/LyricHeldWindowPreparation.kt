package com.musicone

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/** 新曲在独立隐藏列表中准备当前空间位置，确认对应句实际绘制后才放行渐变。 */
internal class LyricHeldWindowPreparation {
    val ready = CompletableDeferred<Unit>()
    private var line = -1
    private var origin = 0f
    private var travel: LyricEntranceTravel? = null
    private var preparedProgress = -1f
    private var expectedOffset = 0f
    private var expectedStamp: LyricDisplayStamp? = null

    suspend fun position(list: LazyListState, current: Int, history: LyricTransitionHistory,
        density: Density, typography: LyricTypography, progress: Float, stamp: LyricDisplayStamp? = null) {
        if (line != current || travel == null || expectedStamp != stamp) {
            list.scrollToItem(current)
            list.revealTopLineForEntrance()
            list.prepareTransitionHistory(history, current, density, typography)
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current } ?: return
            line = current
            origin = item.offset.toFloat()
            travel = LyricEntranceTravel(0f, origin)
        }
        list.scroll(MutatePriority.PreventUserInput) {
            travel!!.moveTo(progress) { scrollBy(it) }
        }
        expectedOffset = travel!!.remainingOffset(progress)
        preparedProgress = progress
        expectedStamp = stamp
    }

    fun drawn(current: Int, offset: Int?, progress: Float, seekOffset: Float, scrolling: Boolean,
        stamp: LyricDisplayStamp? = null) {
        if (expectedStamp == stamp && lyricHeldWindowAligned(current == line, offset, expectedOffset,
                progress, preparedProgress, seekOffset, scrolling)) ready.complete(Unit)
    }
}

internal fun lyricHeldWindowAligned(sameLine: Boolean, offset: Int?, expected: Float,
    progress: Float, preparedProgress: Float, seekOffset: Float, scrolling: Boolean): Boolean =
    sameLine && offset != null && !scrolling && abs(offset - expected) <= 1f &&
        abs(progress - preparedProgress) <= .0005f && abs(seekOffset) <= .5f

internal suspend fun topLinePreparationLoop(request: LyricHeldWindowPreparation,
    list: LazyListState, history: LyricTransitionHistory, density: Density, typography: LyricTypography,
    current: () -> Int, progress: () -> Float, onLine: (Int) -> Unit = {},
    stamp: () -> LyricDisplayStamp? = { null }) {
    var previousLine = -1
    snapshotFlow { Triple(current(), progress(), stamp()) }.collectLatest { (line, fraction, geometry) ->
        if (previousLine != line) { onLine(line); previousLine = line }
        request.position(list, line, history, density, typography, fraction, geometry)
    }
}
