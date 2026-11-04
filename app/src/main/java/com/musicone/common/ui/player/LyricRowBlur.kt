package com.musicone

import android.os.Build
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import kotlin.math.roundToInt

/** 模糊按四分之一 dp 分档复用效果对象；0 档为清晰。 */
internal fun lyricRowBlurStep(
    blurDp: Float,
    disabled: Boolean = ExperiencePreferences.options.disableBlur || ExperiencePreferences.options.disableLyricBlur,
): Int = if (disabled) 0 else (blurDp * 4f).roundToInt().coerceIn(0, 8)

// 效果对象不含歌词内容，同密度下跨行及新旧歌词复用。
private val lyricEffectCache = object : LinkedHashMap<Float, List<BlurEffect?>>(4, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, List<BlurEffect?>>?): Boolean = size > 4
}

internal fun lyricBlurEffects(density: Float): List<BlurEffect?> = synchronized(lyricEffectCache) {
    lyricEffectCache.getOrPut(density) {
        List(9) { step ->
            if (step == 0 || Build.VERSION.SDK_INT < 31) null
            else BlurEffect(step * .25f * density, step * .25f * density, TileMode.Clamp)
        }
    }
}
