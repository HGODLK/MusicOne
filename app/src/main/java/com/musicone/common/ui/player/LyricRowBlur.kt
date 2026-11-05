package com.musicone

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import kotlin.math.abs

// 系统把半径换算成模糊强度时带固定底值，最小一档到清晰的落差天然偏大；靠近清晰的一端加密分档，把这一步压到最小。
private val LYRIC_ROW_BLUR_DP = floatArrayOf(0f, .05f, .15f, .25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

// 收尾保留斜率，不在最后一档上停留；时长短于位移和缩放，变清晰的最后一步落在行仍在运动的时候。
private val LYRIC_ROW_BLUR_EASING = CubicBezierEasing(.4f, 0f, .8f, 1f)

/** 播放切句时的行模糊过渡。 */
internal fun lyricRowBlurPlaybackSpec(): FiniteAnimationSpec<Float> = tween(260, easing = LYRIC_ROW_BLUR_EASING)

/** 模糊按固定档位复用效果对象，取最接近的一档；0 档为清晰。 */
internal fun lyricRowBlurStep(
    blurDp: Float,
    disabled: Boolean = ExperiencePreferences.options.disableBlur || ExperiencePreferences.options.disableLyricBlur,
): Int {
    if (disabled) return 0
    var nearest = 0
    for (step in 1 until LYRIC_ROW_BLUR_DP.size) {
        if (abs(LYRIC_ROW_BLUR_DP[step] - blurDp) < abs(LYRIC_ROW_BLUR_DP[nearest] - blurDp)) nearest = step
    }
    return nearest
}

// 效果对象不含歌词内容，同密度下跨行及新旧歌词复用。
private val lyricEffectCache = object : LinkedHashMap<Float, List<BlurEffect?>>(4, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, List<BlurEffect?>>?): Boolean = size > 4
}

internal fun lyricBlurEffects(density: Float): List<BlurEffect?> = synchronized(lyricEffectCache) {
    lyricEffectCache.getOrPut(density) {
        LYRIC_ROW_BLUR_DP.map { dp ->
            if (dp == 0f || Build.VERSION.SDK_INT < 31) null
            else BlurEffect(dp * density, dp * density, TileMode.Clamp)
        }
    }
}
