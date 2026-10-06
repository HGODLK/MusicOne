package com.musicone.demo

internal data class TabletPlaylistGeometry(
    val coverHeight: Int,
    val informationHeight: Int,
    val actionsHeight: Int,
    val informationTop: Int,
    val actionsTop: Int,
    val descriptionTop: Int,
    val descriptionHeight: Int,
)

/** 只接收预留区域，不接收异步内容的实测高度，保证首次打开和缓存打开使用相同几何。 */
internal fun tabletPlaylistGeometry(width: Int, height: Int, information: Int, actions: Int,
    description: Int, gap: Int, coverLimit: Int = Int.MAX_VALUE): TabletPlaylistGeometry {
    val availableHeight = height.coerceAtLeast(0)
    val actionsHeight = actions.coerceIn(0, availableHeight)
    val spacing = gap.coerceIn(0, (availableHeight - actionsHeight) / 2)
    val informationHeight = information.coerceIn(0, availableHeight - actionsHeight - spacing * 2)
    val coverHeight = minOf(coverLimit.coerceAtLeast(0), tabletPlaylistCoverSide(width,
        availableHeight, informationHeight, actionsHeight, description, spacing * 2))
    val informationTop = coverHeight + if (coverHeight > 0) spacing else 0
    val actionsTop = informationTop + informationHeight + spacing
    val descriptionTop = actionsTop + actionsHeight
    return TabletPlaylistGeometry(coverHeight, informationHeight, actionsHeight,
        informationTop, actionsTop, descriptionTop, availableHeight - descriptionTop)
}
