package com.musicone.demo

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*

/** 头图、标签和空白区域也能纵滑；横向手势仍交给写真和内容分页。 */
@Composable
internal fun Modifier.artistHeaderScroll(
    enabled: Boolean,
    height: Float,
    collapsed: () -> Float,
    onCollapsed: (Float) -> Unit,
    list: LazyListState,
): Modifier {
    val currentCollapsed by rememberUpdatedState(collapsed)
    val updateCollapsed by rememberUpdatedState(onCollapsed)
    val currentList by rememberUpdatedState(list)
    val connection = remember(enabled, height) { object : NestedScrollConnection {
        fun consume(delta: Float): Float {
            val before = currentCollapsed()
            val after = (before - delta).coerceIn(0f, height)
            updateCollapsed(after)
            return before - after
        }
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (enabled && available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (enabled && available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero
    } }
    val scroll = rememberScrollableState { delta ->
        var remaining = delta
        if (remaining > 0f) remaining += currentList.dispatchRawDelta(-remaining)
        val before = currentCollapsed()
        val after = (before - remaining).coerceIn(0f, height)
        updateCollapsed(after)
        remaining -= before - after
        if (remaining < 0f) remaining += currentList.dispatchRawDelta(-remaining)
        delta - remaining
    }
    return nestedScroll(connection).scrollable(scroll, Orientation.Vertical, enabled = enabled)
}
