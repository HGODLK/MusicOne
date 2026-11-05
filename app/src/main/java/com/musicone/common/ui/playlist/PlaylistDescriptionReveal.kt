package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * 歌单简介内联展开／收起的裁剪高度过渡。
 * 两个方向都保持全文排版、只动画裁剪高度；收起动画结束后才切回两行省略，避免多余行在第一帧直接消失。
 */
@Stable
internal class PlaylistDescriptionReveal(expanded: Boolean) {
    /** 0 为两行高度，1 为全文高度。 */
    val progress = Animatable(if (expanded) 1f else 0f)

    /**
     * 进度未回到 0 时仍按全文排版，由裁剪高度把多余行盖住。
     * 直接由进度派生：点“收起”当帧进度仍为 1，不会先缩成两行再弹回；只在跨过 0 时触发重组。
     */
    val opened by derivedStateOf { progress.value > 0f }

    // 最近一次两行排版的实测高度，只在布局阶段读写，不参与重组。
    internal var collapsedHeight = -1

    internal suspend fun settle(expanded: Boolean) {
        progress.animateTo(if (expanded) 1f else 0f, musicMotion(320))
    }
}

@Composable
internal fun rememberPlaylistDescriptionReveal(expanded: Boolean, key: Any?): PlaylistDescriptionReveal {
    val reveal = remember(key) { PlaylistDescriptionReveal(expanded) }
    // 切换方向时取消上一段动画，从当前高度续接。
    LaunchedEffect(reveal, expanded) { reveal.settle(expanded) }
    return reveal
}

/** [fullLayout] 为 true 时文字按全文排版，可见高度在两行与全文之间按进度插值。 */
internal fun Modifier.playlistDescriptionReveal(reveal: PlaylistDescriptionReveal, fullLayout: Boolean): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val collapsed = reveal.collapsedHeight
        val height = when {
            !fullLayout -> placeable.height.also { reveal.collapsedHeight = it }
            collapsed < 0 -> placeable.height
            else -> (collapsed + (placeable.height - collapsed) * reveal.progress.value).roundToInt()
        }
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
