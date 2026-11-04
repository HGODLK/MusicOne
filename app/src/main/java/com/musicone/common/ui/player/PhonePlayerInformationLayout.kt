package com.musicone

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal data class PhonePlayerInformationGeometry(
    val textScale: Float,
    val artistTop: Float,
    val favoriteTop: Float,
)

/** 以歌名顶部为原点，横竖屏共用歌曲资料的相对纵向布局。 */
internal fun phonePlayerInformationGeometry(
    titleHeight: Int,
    headerProgress: Float,
    density: Density,
): PhonePlayerInformationGeometry {
    val textScale = motionLerp(1f, .9f, headerProgress)
    return PhonePlayerInformationGeometry(
        textScale = textScale,
        artistTop = titleHeight * textScale,
        favoriteTop = with(density) { 2.dp.toPx() } * headerProgress,
    )
}
