package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 底栏内容的最大宽度：并排与经典两种布局、迷你播放条共用，宽屏上居中。 */
internal val BottomBarMaxWidth = 1_280.dp
/** 与迷你播放条的左右留白一致；两者之间留 8dp。 */
private val BottomBarRowEdge = 12.dp
private val BottomBarRowGap = 8.dp

/** 播放条的槽位宽度：胶囊可见时让出“胶囊＋间距”，胶囊收起后占满整行。 */
internal fun bottomBarRowMiniWidth(contentWidth: Int, capsuleWidth: Int, gap: Int, visibility: Float): Int =
    (contentWidth - ((capsuleWidth + gap) * visibility.coerceIn(0f, 1f)).roundToInt()).coerceAtLeast(0)

/** 胶囊始终贴在播放条外侧，播放条变宽时把它推出内容区；反转时整行镜像。 */
internal fun bottomBarRowCapsuleX(contentStart: Int, contentWidth: Int, capsuleWidth: Int, edge: Int, gap: Int,
    visibility: Float, mirrored: Boolean): Int {
    val shift = ((capsuleWidth + gap) * visibility.coerceIn(0f, 1f)).roundToInt()
    return if (mirrored) contentStart + edge + shift - gap - capsuleWidth
    else contentStart + contentWidth - edge - shift + gap
}

/**
 * 并排底栏：迷你播放条与导航胶囊同一行。
 * 胶囊的显隐进度沿用经典布局的同一个值，只把“变高”换成“变宽”，返回手势中途可停在任意宽度；
 * 没有歌曲时胶囊单独居中，播放条出现后滑到一侧。
 */
@Composable
internal fun BottomPlayerBarRow(
    mirrored: Boolean,
    capsuleWidth: Dp,
    capsuleVisibility: () -> Float,
    miniPresence: () -> Float,
    modifier: Modifier = Modifier,
    mini: @Composable () -> Unit,
    capsule: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { mini() }
            Box(Modifier.graphicsLayer { alpha = capsuleVisibility().coerceIn(0f, 1f) }) { capsule() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val contentWidth = minOf(constraints.maxWidth, BottomBarMaxWidth.roundToPx())
        val contentStart = (constraints.maxWidth - contentWidth) / 2
        val capsulePx = capsuleWidth.roundToPx()
        val edge = BottomBarRowEdge.roundToPx()
        val gap = BottomBarRowGap.roundToPx()
        val visibility = capsuleVisibility().coerceIn(0f, 1f)
        val capsulePlaceable = measurables[1].measure(
            Constraints(minWidth = capsulePx, maxWidth = capsulePx, maxHeight = constraints.maxHeight))
        val miniWidth = bottomBarRowMiniWidth(contentWidth, capsulePx, gap, visibility)
        val miniPlaceable = measurables[0].measure(
            Constraints(minWidth = miniWidth, maxWidth = miniWidth, maxHeight = constraints.maxHeight))
        // 有播放条时行高恒定；没有播放条时随胶囊显隐收起，与经典布局的占位一致。
        val height = maxOf(miniPlaceable.height, (capsulePlaceable.height * visibility).roundToInt())
        layout(constraints.maxWidth, height) {
            miniPlaceable.place(if (mirrored) contentStart + contentWidth - miniWidth else contentStart,
                height - miniPlaceable.height)
            val side = bottomBarRowCapsuleX(contentStart, contentWidth, capsulePx, edge, gap, visibility, mirrored)
            val center = (constraints.maxWidth - capsulePx) / 2
            val x = center + ((side - center) * miniPresence().coerceIn(0f, 1f)).roundToInt()
            capsulePlaceable.place(x, (height - capsulePlaceable.height).coerceAtLeast(0))
        }
    }
}
