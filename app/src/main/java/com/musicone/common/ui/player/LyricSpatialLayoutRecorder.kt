package com.musicone

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.Density

/** 列宽不变时复用字号计算；位置保护仍在每次真实绘制时更新。 */
internal class LyricSpatialLayoutRecorder(
    private val travel: PhoneLyricsSpatialTravel,
    private val density: Density,
    private val mode: PlayerLayoutMode,
) {
    private var width = -1
    private var fontSize: LyricFontSize? = null
    private var typography: LyricTypography? = null

    fun record(layout: LazyListLayoutInfo, protection: LyricBlurProtection) {
        val actualWidth = layout.viewportSize.width
        val currentFontSize = ExperiencePreferences.options.lyricFontSize
        val type = typography?.takeIf { width == actualWidth && fontSize == currentFontSize }
            ?: lyricTypographyForWidth(mode, actualWidth / density.density, currentFontSize).also {
                width = actualWidth
                fontSize = currentFontSize
                typography = it
            }
        travel.record(layout, density, protection, type)
    }
}
