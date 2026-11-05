package com.musicone

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** 外层已消费输入法和导航栏，剩余避让只补到播放控件上沿。 */
internal fun aiBottomClearance(playerBottom: Int, imeBottom: Int, navigationBottom: Int): Int =
    (playerBottom - maxOf(imeBottom, navigationBottom)).coerceAtLeast(0)

/** 在测量阶段读取输入法动画帧，避免组合阶段的显隐开关造成末段落到播放条后面。 */
@Composable
internal fun rememberAiBottomClearance(bottomInset: Dp): () -> Dp {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    return { with(density) { aiBottomClearance(bottomInset.roundToPx(), ime.getBottom(this), navigation.getBottom(this)).toDp() } }
}

internal fun Modifier.aiBottomClearance(clearance: () -> Dp) = layout { measurable, constraints ->
    val inset = clearance().roundToPx().coerceAtMost(constraints.maxHeight)
    val content = measurable.measure(constraints.copy(
        minHeight = (constraints.minHeight - inset).coerceAtLeast(0),
        maxHeight = constraints.maxHeight - inset,
    ))
    layout(content.width, content.height + inset) { content.placeRelative(0, 0) }
}
