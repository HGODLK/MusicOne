package com.musicone

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

internal val LocalPhoneLyricsColumnWidth = staticCompositionLocalOf<Int?> { null }

/** 在旋转宿主外量取一次竖屏窗口，避免目标约束交付前用旧宽高算出中间列宽。 */
@Composable
internal fun PhoneLyricsColumnHost(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = phoneLyricsColumnWidth(constraints.maxWidth, density)
        CompositionLocalProvider(LocalPhoneLyricsColumnWidth provides column, content = content)
    }
}

/** 以未旋转窗口的竖屏正文为唯一列宽，先按实际像素取整，避免两次边距取整改变换行。 */
internal fun phoneLyricsColumnWidth(windowWidth: Int,
    density: Density): Int = (windowWidth -
    with(density) { CompactPlayerLayoutSpec.horizontalPadding.roundToPx() } * 2).coerceAtLeast(1)

/** 横屏只增加原触摸留白，正文可用宽度仍与竖屏相同。 */
internal fun phoneLandscapeLyricsWidth(column: Int, touchPaddingPx: Float): Int =
    column + touchPaddingPx.roundToInt().coerceAtLeast(0)
