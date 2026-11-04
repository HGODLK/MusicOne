package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import com.musicone.phonePlayerLandscapePadding
import com.musicone.source.sdk.SourcePlayerInsets

/** 在宿主窗口读取安全边，沿用原生播放器的朝向映射，独立组合不再猜测窗口方向。 */
@Composable
internal fun sourcePlayerSafeDrawingInsets(rotation: Int): SourcePlayerInsets {
    val padding = phonePlayerLandscapePadding(rotation)
    return SourcePlayerInsets(
        padding.calculateLeftPadding(LayoutDirection.Ltr), padding.calculateTopPadding(),
        padding.calculateRightPadding(LayoutDirection.Ltr), padding.calculateBottomPadding(),
    )
}
