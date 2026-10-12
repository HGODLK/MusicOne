package com.musicone.demo

import androidx.compose.ui.unit.Density
import kotlin.math.pow

/** 歌词列宽决定小幅字号适配；同一组参数供正文、译文与过渡几何共用。 */
internal data class LyricTypography(
    val activeSp: Float,
    val activeLineSp: Float,
    val translationSp: Float,
    val translationLineSp: Float,
    val upcomingSp: Float,
) {
    val estimatedRowHeightDp: Float get() = activeLineSp + translationLineSp + 12f
}

internal fun lyricTypographyForWidth(mode: PlayerLayoutMode, columnWidthDp: Float): LyricTypography {
    val large = columnWidthDp >= 400f
    val reference = if (mode == PlayerLayoutMode.COMPACT_SINGLE) 340f else 600f
    val ratio = (columnWidthDp.coerceAtLeast(1f) / reference).pow(.12f).coerceIn(.94f, 1.06f)
    val base = if (mode == PlayerLayoutMode.COMPACT_SINGLE) 32f else 44f
    return LyricTypography(
        activeSp = base * ratio,
        activeLineSp = (if (large) 56f else 42f) * ratio,
        translationSp = (if (large) 14f else 13f) * ratio,
        translationLineSp = (if (large) 20f else 19f) * ratio,
        upcomingSp = (if (large) 40f else 28f) * ratio,
    )
}

/** 只在歌词子树中覆盖系统字体倍率，保留设备显示密度。 */
internal fun lyricDisplayDensity(density: Density): Density = Density(density.density, 1f)
