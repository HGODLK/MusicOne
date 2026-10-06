package com.musicone.demo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 横竖屏共用稳定文字预算，竖屏操作行在正常位置排列，滚动时吸顶。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabletPlaylistPane(
    scroll: LazyListState,
    bottomInset: Dp,
    contentBottomPadding: Dp,
    usePageBackground: Boolean,
    wide: Boolean,
    album: Boolean = false,
    cover: @Composable (Modifier) -> Unit,
    introduction: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    description: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tracks: LazyListScope.() -> Unit = {},
) {
    val slots = rememberTabletPlaylistTextSlots(album)
    CompositionLocalProvider(LocalTabletPlaylistTextSlots provides slots) {
        if (wide) {
            TabletPlaylistInformation(
                bottomInset, usePageBackground, slots, cover, introduction, actions, description, modifier,
            )
        } else {
            val pageColor = PlaylistPageColor
            BoxWithConstraints(modifier.fillMaxSize()) {
                val contentWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
                val coverModifier = if (usePageBackground) {
                    Modifier.width(contentWidth).height(112.dp)
                } else {
                    val side = with(androidx.compose.ui.platform.LocalDensity.current) {
                        tabletPlaylistCoverSide(contentWidth.roundToPx(),
                            (maxHeight - bottomInset - 36.dp).roundToPx(),
                            slots.information.roundToPx() + 12.dp.roundToPx(),
                            slots.actions.roundToPx(), slots.description.roundToPx(), 0).toDp()
                    }
                    Modifier.size(side)
                }
                LazyColumn(
                    state = scroll,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = contentBottomPadding),
                ) {
                    item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { cover(coverModifier) } }
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)
                            .height(slots.information).clipToBounds()) { introduction() }
                    }
                    stickyHeader(key = "playlist-playback-actions") {
                        Box(Modifier.fillMaxWidth().background(pageColor).padding(horizontal = 24.dp)
                            .height(slots.actions).playlistDetailReveal(),
                            contentAlignment = Alignment.Center) { actions() }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                            .playlistDetailReveal()) { description() }
                    }
                    tracks()
                }
            }
        }
    }
}

/** 封面和操作栏位置只由窗口、字体及固定行数决定，简介测量不再改变封面。 */
@Composable
private fun TabletPlaylistInformation(
    bottomInset: Dp,
    usePageBackground: Boolean,
    slots: TabletPlaylistTextSlots,
    cover: @Composable (Modifier) -> Unit,
    introduction: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    description: @Composable () -> Unit,
    modifier: Modifier,
) {
    Layout(
        modifier = modifier.fillMaxHeight().padding(top = 12.dp, bottom = bottomInset + 24.dp),
        content = {
            Box { cover(Modifier.fillMaxSize()) }
            Box { introduction() }
            Box(Modifier.playlistDetailReveal()) { actions() }
            Box(Modifier.clipToBounds().verticalScroll(rememberScrollState()).playlistDetailReveal()) { description() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val contentWidth = (width - 48.dp.roundToPx()).coerceAtLeast(0)
        val gap = 12.dp.roundToPx()
        val geometry = tabletPlaylistGeometry(contentWidth, height, slots.information.roundToPx(),
            slots.actions.roundToPx(), slots.description.roundToPx(), gap,
            if (usePageBackground) 136.dp.roundToPx() else Int.MAX_VALUE)
        val action = children[2].measure(Constraints(maxWidth = contentWidth,
            minHeight = geometry.actionsHeight, maxHeight = geometry.actionsHeight))
        val information = children[1].measure(Constraints(maxWidth = contentWidth,
            minHeight = geometry.informationHeight, maxHeight = geometry.informationHeight))
        val coverHeight = geometry.coverHeight
        val artwork = children[0].measure(Constraints.fixed(
            if (usePageBackground) contentWidth else coverHeight, coverHeight))
        val details = children[3].measure(Constraints(maxWidth = contentWidth,
            maxHeight = geometry.descriptionHeight))
        layout(width, height) {
            artwork.placeRelative((width - artwork.width) / 2, 0)
            information.placeRelative((width - information.width) / 2, geometry.informationTop)
            action.placeRelative((width - action.width) / 2, geometry.actionsTop)
            details.placeRelative((width - details.width) / 2, geometry.descriptionTop)
        }
    }
}

internal fun tabletPlaylistCoverSide(width: Int, height: Int, information: Int, actions: Int,
    description: Int, gaps: Int): Int = minOf(width.coerceAtLeast(0),
    (height - information - actions - description - gaps).coerceAtLeast(0))
