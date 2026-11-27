package com.musicone

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** 外层已经处理了输入法和导航栏的避让，这里只需要补出播放条多出来的那截高度。 */
internal fun aiBottomClearance(playerBottom: Int, imeBottom: Int, navigationBottom: Int): Int =
    (playerBottom - maxOf(imeBottom, navigationBottom)).coerceAtLeast(0)

/** 在布局测量阶段实时读输入法高度，避免输入法收起动画时最后一条消息短暂被播放条挡住。 */
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
