package com.musicone.demo

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 扩大横滑区域的绘制视口，标题与首项边距仍由内容保留，外层首页负责页面裁剪。 */
internal fun Modifier.qqRecommendationEdgeLayout(edgeInset: Dp): Modifier =
    if (edgeInset == 0.dp) this else layout { measurable, constraints ->
        val inset = edgeInset.roundToPx()
        val placeable = measurable.measure(constraints.copy(
            minWidth = constraints.maxWidth + inset * 2,
            maxWidth = constraints.maxWidth + inset * 2,
        ))
        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-inset, 0) }
    }
