package com.musicone.demo

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

/** 搜索折叠与歌单筛选共用可中断的行高、透明度动画，邻行始终按当前实际高度排布。 */
@Composable
internal fun SearchResultRowVisibility(transition: MutableTransitionState<Boolean>,
    animated: Boolean = true, reserveEntrySpace: Boolean = false, content: @Composable () -> Unit) {
    val sizeMillis = if (animated) 320 else 0
    val fadeMillis = if (animated) 240 else 0
    AnimatedVisibility(transition,
        // 批量恢复歌单时预留半行，避免零高度首帧迫使 LazyColumn 组合整张长歌单。
        enter = expandVertically(musicMotion(sizeMillis), expandFrom = Alignment.Bottom,
            initialHeight = { if (reserveEntrySpace) (it / 2).coerceAtLeast(1) else 0 }) + fadeIn(musicMotion(fadeMillis)),
        exit = shrinkVertically(musicMotion(sizeMillis), shrinkTowards = Alignment.Bottom) + fadeOut(musicMotion(fadeMillis))) {
        content()
    }
}
