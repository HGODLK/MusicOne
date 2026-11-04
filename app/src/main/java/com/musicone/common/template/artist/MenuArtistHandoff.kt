package com.musicone

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** 与菜单来源矩形同时保存，详情挂载后的搜索页重测不能改写本次交接起点。 */
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
