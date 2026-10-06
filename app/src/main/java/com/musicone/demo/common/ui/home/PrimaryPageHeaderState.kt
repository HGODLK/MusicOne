package com.musicone.demo

import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Stable
internal class PrimaryPageHeaderState {
    private val homeTitleVisibility = mutableFloatStateOf(1f)
    private val myTitleVisibility = mutableFloatStateOf(1f)
    private val scrollToTopActions = arrayOfNulls<() -> Unit>(MusicOnePage.entries.size)

    fun titleVisibility(page: MusicOnePage): Float = when (page) {
        MusicOnePage.HOME -> homeTitleVisibility.floatValue
        MusicOnePage.MY -> myTitleVisibility.floatValue
    }

    fun update(page: MusicOnePage, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, fadeDistance: Float) {
        val visibility = primaryTitleVisibility(firstVisibleItemIndex, firstVisibleItemScrollOffset, fadeDistance)
        when (page) {
            MusicOnePage.HOME -> homeTitleVisibility.floatValue = visibility
            MusicOnePage.MY -> myTitleVisibility.floatValue = visibility
        }
    }

    fun bindScrollToTop(page: MusicOnePage, action: () -> Unit) {
        scrollToTopActions[page.ordinal] = action
    }

    fun unbindScrollToTop(page: MusicOnePage, action: () -> Unit) {
        if (scrollToTopActions[page.ordinal] === action) scrollToTopActions[page.ordinal] = null
    }

    fun requestScrollToTop(page: MusicOnePage) {
        scrollToTopActions[page.ordinal]?.invoke()
    }
}

@Composable
internal fun rememberPrimaryPageHeaderState(): PrimaryPageHeaderState = remember { PrimaryPageHeaderState() }

@Composable
internal fun ReportPrimaryHeaderScroll(page: MusicOnePage, state: LazyListState) {
    val fadeDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val headerState = LocalPrimaryPageHeaderState.current
    val scope = rememberCoroutineScope()
    DisposableEffect(page, state, headerState, scope) {
        val action: () -> Unit = { scope.launch { state.scrollPrimaryPageToTop() } }
        headerState.bindScrollToTop(page, action)
        onDispose { headerState.unbindScrollToTop(page, action) }
    }
    LaunchedEffect(state, headerState, fadeDistance) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> headerState.update(page, index, offset, fadeDistance) }
    }
}

@Composable
internal fun ReportPrimaryHeaderScroll(page: MusicOnePage, state: LazyGridState) {
    val fadeDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val headerState = LocalPrimaryPageHeaderState.current
    val scope = rememberCoroutineScope()
    DisposableEffect(page, state, headerState, scope) {
        val action: () -> Unit = { scope.launch { state.scrollPrimaryPageToTop() } }
        headerState.bindScrollToTop(page, action)
        onDispose { headerState.unbindScrollToTop(page, action) }
    }
    LaunchedEffect(state, headerState, fadeDistance) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> headerState.update(page, index, offset, fadeDistance) }
    }
}

@Composable
internal fun ReportPrimaryHeaderScroll(page: MusicOnePage, state: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState) {
    val fadeDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val headerState = LocalPrimaryPageHeaderState.current
    val scope = rememberCoroutineScope()
    DisposableEffect(page, state, headerState, scope) {
        val action: () -> Unit = { scope.launch { state.scrollPrimaryPageToTop() } }
        headerState.bindScrollToTop(page, action)
        onDispose { headerState.unbindScrollToTop(page, action) }
    }
    LaunchedEffect(state, headerState, fadeDistance) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> headerState.update(page, index, offset, fadeDistance) }
    }
}

private suspend fun LazyListState.scrollPrimaryPageToTop() {
    stopScroll()
    if (firstVisibleItemIndex > 1) scrollToItem(1)
    animateScrollToItem(0)
}

private suspend fun LazyGridState.scrollPrimaryPageToTop() {
    stopScroll()
    if (firstVisibleItemIndex > 1) scrollToItem(1)
    animateScrollToItem(0)
}

private suspend fun androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState.scrollPrimaryPageToTop() {
    stopScroll()
    scrollToItem(if (firstVisibleItemIndex > 1) 1 else firstVisibleItemIndex)
    animateScrollToItem(0)
}

internal fun primaryTitleVisibility(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    fadeDistance: Float,
): Float {
    if (firstVisibleItemIndex > 0) return 0f
    if (fadeDistance <= 0f) return if (firstVisibleItemScrollOffset == 0) 1f else 0f
    return (1f - firstVisibleItemScrollOffset / fadeDistance).coerceIn(0f, 1f)
}

internal val LocalPrimaryPageHeaderState = androidx.compose.runtime.staticCompositionLocalOf<PrimaryPageHeaderState> {
    error("PrimaryPageHeaderState 未提供")
}
