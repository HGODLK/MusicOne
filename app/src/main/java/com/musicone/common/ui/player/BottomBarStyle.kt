package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 底栏的两种布局：经典为播放条在上、导航胶囊在下，反转只镜像播放条；并排为同一行，可整行左右反转。 */
internal data class BottomBarStyle(val classic: Boolean, val mirrored: Boolean) {
    /** 并排时播放条与胶囊共用同一圆角；经典保持原值，飞行玻璃的起始圆角随之取值。 */
    val surfaceCorner: Dp get() = if (classic) 19.dp else 20.dp

    /** 浅色模式下玻璃的白度：并排时播放条与胶囊统一为 30%；经典播放条保持 28%（经典胶囊另为 55%）。 */
    val lightTint: Float get() = if (classic) .28f else .30f
}

/** 默认按经典布局，底栏以外单独使用迷你播放条时行为不变。 */
internal val LocalBottomBarStyle = staticCompositionLocalOf { BottomBarStyle(classic = true, mirrored = false) }

/** 并排底栏在手机根页面收起收藏与下一首；为空表示始终显示。 */
internal val LocalMiniAccessoryReveal = staticCompositionLocalOf<(() -> Float)?> { null }

/** 关闭页面切换胶囊后经典布局只剩播放条，按并排处理；保存的“经典底栏”选择不改。反转在两种布局下都生效。 */
internal fun bottomBarStyleFor(options: ExperienceOptions): BottomBarStyle {
    val classic = options.classicBottomBar && !options.hideNavigationCapsule
    return BottomBarStyle(classic, options.reverseBottomBar)
}

@Composable
internal fun rememberBottomBarStyle(): BottomBarStyle {
    val options = ExperiencePreferences.options
    return remember(options.classicBottomBar, options.reverseBottomBar, options.hideNavigationCapsule) {
        bottomBarStyleFor(options)
    }
}

/**
 * 收藏与下一首随导航胶囊让出的宽度展开：宽度和透明度读同一进度。
 * 裁剪放在最外层，未完全露出的按钮边界不完整，不会登记为播放页飞行的起点。
 */
internal fun Modifier.miniAccessoryReveal(reveal: (() -> Float)?): Modifier =
    if (reveal == null) this else this
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val width = (placeable.width * reveal().coerceIn(0f, 1f)).roundToInt()
            layout(width, placeable.height) { placeable.place(0, 0) }
        }
        .graphicsLayer { alpha = reveal().coerceIn(0f, 1f) }
