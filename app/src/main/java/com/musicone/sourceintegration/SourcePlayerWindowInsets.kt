package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import com.musicone.phonePlayerLandscapePadding
import com.musicone.source.sdk.SourcePlayerInsets

/** 从宿主窗口读系统安全边距并按旋转方向映射好，直接交给自建播放页使用。 */
@Composable
internal fun sourcePlayerSafeDrawingInsets(rotation: Int): SourcePlayerInsets {
    val padding = phonePlayerLandscapePadding(rotation)
    return SourcePlayerInsets(
        padding.calculateLeftPadding(LayoutDirection.Ltr), padding.calculateTopPadding(),
        padding.calculateRightPadding(LayoutDirection.Ltr), padding.calculateBottomPadding(),
    )
}
