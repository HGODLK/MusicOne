package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** 进入详情前同时记下头像起点和菜单位置，防止详情页挂载触发底页重测把起点改乱。 */
internal data class MenuArtistHandoff(
    val key: String,
    val bounds: Rect,
    val mark: String,
    val avatar: ArtistAvatarPresentation,
    val visible: Boolean,
    val rotation: Int = 0,
)

internal fun MenuArtistArtworkState.captureHandoff(): List<MenuArtistHandoff> = slots.map {
    MenuArtistHandoff(it.key, it.bounds, it.mark, it.avatar, it.visible, rotation)
}

internal fun EntityFrame.retainsMenuArtwork(state: MenuArtistArtworkState): Boolean =
    motion.moving && sourceMenu?.artistArtworkState === state && !motion.hostBounds.isEmpty &&
        menuImage != null

internal fun menuArtistHandoffOffset(bounds: Rect, host: Rect, translationY: Float): IntOffset =
    IntOffset((bounds.left - host.left).roundToInt(), (bounds.top - host.top + translationY).roundToInt())
