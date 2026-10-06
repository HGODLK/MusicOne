package com.musicone.demo

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.offset

/** 只扩展底部实体导航区；手势条、隐藏导航及横屏侧边导航不增加歌词高度。 */
@Composable
internal fun playerButtonNavigationBottomExtension(): Int {
    if (Build.VERSION.SDK_INT < 31 || ExperiencePreferences.options.disableBlur) return 0
    val density = LocalDensity.current
    return buttonNavigationBottomExtension(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.tappableElement.getBottom(density),
    )
}

internal fun buttonNavigationBottomExtension(navigationBottom: Int, tappableBottom: Int): Int =
    if (tappableBottom > 0) navigationBottom.coerceAtLeast(0) else 0

/** 扩展歌词及模糊采样的子视口，但向父布局报告原高度，不挤动封面和控件。 */
internal fun Modifier.playerLyricsDrawExtension(bottom: Int): Modifier =
    if (bottom <= 0) this else layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.offset(vertical = bottom))
        layout(placeable.width, (placeable.height - bottom).coerceAtLeast(0)) {
            placeable.place(0, 0)
        }
    }
