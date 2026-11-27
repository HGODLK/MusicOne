package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 头图和内容区只测一次，折叠距离只在 layout 放置阶段生效，避免拖动时反复触发整页重新测量。 */
@Composable
internal fun ArtistCollapsingLayout(
    heroHeight: Dp,
    collapseLimit: Float,
    collapsed: () -> Float,
    onCollapsed: (Float) -> Unit,
    list: LazyListState,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .artistHeaderScroll(true, collapseLimit, collapsed, onCollapsed, list),
    ) {
        val heroPixels = with(LocalDensity.current) { heroHeight.toPx() }
        val contentHeight = (maxHeight - 64.dp).coerceAtLeast(0.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .offset { IntOffset(0, -collapsed().roundToInt()) },
        ) {
            header()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(contentHeight)
                .offset {
                    IntOffset(0, (heroPixels - collapsed()).roundToInt())
                },
        ) {
            content()
        }
    }
}
