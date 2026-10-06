package com.musicone.demo

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

internal val LocalRecommendationVisible = androidx.compose.runtime.staticCompositionLocalOf { true }

/** 只在首页可见时预取；滚动状态仍由可保存的网格持有。 */
@Composable
internal fun ObserveQqMusicFeed(
    viewModel: QqMusicFeedViewModel,
    grid: LazyStaggeredGridState,
    sessionRevision: Long,
    page: MusicOnePage,
) {
    val feed by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motion = LocalPlaylistMotion.current
    var userDragArmed by remember(sessionRevision, feed.generation) { mutableStateOf(false) }
    val latestFeed by rememberUpdatedState(feed)
    val latestPage by rememberUpdatedState(page)
    val visible = LocalRecommendationVisible.current && page == MusicOnePage.HOME && motion?.mounted != true
    val latestVisible by rememberUpdatedState(visible)
    val pageInteraction = LocalPageInteraction.current
    DisposableEffect(grid, pageInteraction) {
        // 直接读取滚动状态，覆盖拖动、惯性和返回顶部，不让取色与滚动争用渲染线程。
        pageInteraction?.bindScroll(grid) { latestVisible && grid.isScrollInProgress }
        onDispose { pageInteraction?.unbindScroll(grid) }
    }
    LaunchedEffect(sessionRevision) { viewModel.configure(sessionRevision) }
    LaunchedEffect(grid, sessionRevision, feed.generation) {
        grid.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userDragArmed = true
                val state = latestFeed
                val layout = grid.layoutInfo
                if (latestVisible && latestPage == MusicOnePage.HOME && motion?.mounted != true &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !state.loading && !state.automaticLoading && !state.refreshRequired &&
                    layout.visibleItemsInfo.maxOfOrNull { it.index }?.let {
                        it >= layout.totalItemsCount - maxOf(6, layout.visibleItemsInfo.size)
                    } == true) {
                    viewModel.retry()
                }
            }
        }
    }
    LaunchedEffect(grid, feed.cards.size, feed.loading, feed.automaticLoading, page, lifecycle, visible) {
        if (feed.loading || !feed.automaticLoading || !visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                val layout = grid.layoutInfo
                userDragArmed && motion?.mounted != true && layout.visibleItemsInfo.maxOfOrNull { it.index }
                    ?.let { it >= layout.totalItemsCount - maxOf(6, layout.visibleItemsInfo.size) } == true
            }.collect { nearEnd ->
                if (nearEnd && userDragArmed) {
                    viewModel.loadMore()
                }
            }
        }
    }
}
