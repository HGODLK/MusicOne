package com.musicone.demo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 保留原控件的几何比例，只柔化尖端和拐角。 */
internal object PlayerControlIcons {
    val FastForward = doubleTriangle("柔角快进", false)
    val FastRewind = doubleTriangle("柔角快退", true)
    val Play = ImageVector.Builder("柔角播放", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 6.05f)
            curveTo(8f, 5.2f, 8.3f, 5.1f, 9f, 5.54f)
            lineTo(18.35f, 11.35f)
            curveTo(19.2f, 11.88f, 19.2f, 12.12f, 18.35f, 12.65f)
            lineTo(9f, 18.46f)
            curveTo(8.3f, 18.9f, 8f, 18.8f, 8f, 17.95f)
            close()
        }
    }.build()
    val Pause = ImageVector.Builder("柔角暂停", 24.dp, 24.dp, 24f, 24f).apply {
        for (left in listOf(6f, 14f)) path(fill = SolidColor(Color.Black)) {
            moveTo(left + .9f, 5f)
            lineTo(left + 3.1f, 5f)
            curveTo(left + 3.6f, 5f, left + 4f, 5.4f, left + 4f, 5.9f)
            lineTo(left + 4f, 18.1f)
            curveTo(left + 4f, 18.6f, left + 3.6f, 19f, left + 3.1f, 19f)
            lineTo(left + .9f, 19f)
            curveTo(left + .4f, 19f, left, 18.6f, left, 18.1f)
            lineTo(left, 5.9f)
            curveTo(left, 5.4f, left + .4f, 5f, left + .9f, 5f)
            close()
        }
    }.build()

    private fun doubleTriangle(name: String, reverse: Boolean): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            fun x(value: Float) = if (reverse) 24f - value else value
            for (left in listOf(4f, 13f)) path(fill = SolidColor(Color.Black)) {
                moveTo(x(left), 7.25f)
                curveTo(x(left), 6.1f, x(left + .35f), 5.95f, x(left + 1.25f), 6.55f)
                lineTo(x(left + 6.85f), 11.0f)
                curveTo(x(left + 7.85f), 11.72f, x(left + 7.85f), 12.28f, x(left + 6.85f), 13.0f)
                lineTo(x(left + 1.25f), 17.45f)
                curveTo(x(left + .35f), 18.05f, x(left), 17.9f, x(left), 16.75f)
                close()
            }
        }.build()
}
