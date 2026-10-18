package com.musicone.demo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun playlistGridRows(count: Int, columns: Int): Int =
    if (count <= 0) 0 else (count - 1) / columns.coerceAtLeast(1) + 1

/** 删除整行时逐帧释放滚动范围，避免 LazyGrid 在一帧内夹紧到底部。 */
@Composable
internal fun playlistGridTrailingSpace(count: Int, columns: Int, cardSide: Dp): Dp {
    val target = (cardSide + 20.dp) * playlistGridRows(count, columns)
    val extent by animateDpAsState(target, musicSpring(), label = "歌单网格高度接续")
    return (extent - target).coerceAtLeast(0.dp)
}
