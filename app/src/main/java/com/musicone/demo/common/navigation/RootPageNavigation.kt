package com.musicone.demo

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class RootPagerAnchor(val page: Int, val offsetFraction: Float)

internal data class PageTitleMotion(
    val offsetFraction: Float,
    val alpha: Float,
)

internal fun pageTitleMotion(page: Int, pagerPosition: Float): PageTitleMotion {
    val offset = page - pagerPosition
    return PageTitleMotion(
        offsetFraction = offset,
        alpha = (1f - abs(offset)).coerceIn(0f, 1f),
    )
}

internal fun rootPageForPosition(position: Float): MusicOnePage =
    MusicOnePage.entries[position.coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat()).roundToInt()]

internal fun rootPagerAnchorForPosition(position: Float): RootPagerAnchor {
    val lastPage = MusicOnePage.entries.lastIndex
    val clamped = position.coerceIn(0f, lastPage.toFloat())
    val lower = floor(clamped).toInt()
    if (lower >= lastPage) return RootPagerAnchor(lastPage, 0f)
    val fraction = clamped - lower
    return if (fraction <= .5f) RootPagerAnchor(lower, fraction)
    else RootPagerAnchor(lower + 1, fraction - 1f)
}

@Stable
internal class RootPageNavigation(
    val pagerState: PagerState,
    private val scope: CoroutineScope,
    private val beforePageChange: suspend (MusicOnePage) -> Unit = {},
    private val onNavigationContact: () -> Unit = {},
    private val navigationDragBlocked: () -> Boolean = { false },
) {
    private var animationJob: Job? = null

    val position: Float
        get() = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())

    fun beginNavigationInteraction() = onNavigationContact()

    fun isNavigationDragBlocked(): Boolean = navigationDragBlocked()

    fun animateTo(page: MusicOnePage) {
        animationJob?.cancel()
        animationJob = scope.launch {
            beforePageChange(page)
            pagerState.animateScrollToPage(
                page = page.ordinal,
                animationSpec = musicMotion(360),
            )
        }
    }

    fun dragTo(position: Float) {
        animationJob?.cancel()
        val anchor = rootPagerAnchorForPosition(position)
        animationJob = scope.launch {
            beforePageChange(rootPageForPosition(position))
            pagerState.requestScrollToPage(anchor.page, anchor.offsetFraction)
        }
    }
}

@Composable
internal fun rememberRootPageNavigation(
    selectedPage: MusicOnePage,
    onPageSettled: (MusicOnePage) -> Unit,
    beforePageChange: suspend (MusicOnePage) -> Unit = {},
    onNavigationContact: () -> Unit = {},
    navigationDragBlocked: () -> Boolean = { false },
): RootPageNavigation {
    val pagerState = rememberPagerState(
        initialPage = selectedPage.ordinal,
        pageCount = { MusicOnePage.entries.size },
    )
    val scope = rememberCoroutineScope()
    val currentOnPageSettled = rememberUpdatedState(onPageSettled)
    val currentBeforePageChange = rememberUpdatedState(beforePageChange)
    val currentOnNavigationContact = rememberUpdatedState(onNavigationContact)
    val currentNavigationDragBlocked = rememberUpdatedState(navigationDragBlocked)
    val navigation = remember(pagerState, scope) {
        RootPageNavigation(
            pagerState,
            scope,
            beforePageChange = { currentBeforePageChange.value(it) },
            onNavigationContact = { currentOnNavigationContact.value() },
            navigationDragBlocked = { currentNavigationDragBlocked.value() },
        )
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { currentOnPageSettled.value(MusicOnePage.entries[it]) }
    }
    LaunchedEffect(selectedPage) {
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedPage.ordinal) {
            navigation.animateTo(selectedPage)
        }
    }
    return navigation
}
