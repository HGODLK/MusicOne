package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 完整列表只改变位移；过渡留白来自已排版的歌词段，不新增裁剪边界。 */
internal class PhoneLyricsSpatialTravel {
    var blurProtection = LyricBlurProtection.HANDOFF
        private set
    private var measuredHeightPx = 0f
    private var measuredSoftnessPx = 1f
    var compactHeightPx = 0f
        private set
    var softnessPx = 1f
        private set

    fun record(layout: LazyListLayoutInfo, density: Density, protection: LyricBlurProtection) {
        blurProtection = protection
        if (!protection.allowWidening) return
        val rows = layout.visibleItemsInfo.filter {
            it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset
        }.take(2)
        val first = rows.firstOrNull() ?: return
        val largeText = layout.viewportSize.width >= with(density) { 400.dp.toPx() }
        fun shortHeight(translated: Boolean) = with(density) {
            (if (largeText) 56.sp else MusicOneTextStyles.lyricActive.lineHeight).toPx() +
                10.dp.toPx() + if (translated) {
                    (if (largeText) 20.sp else MusicOneTextStyles.lyricTranslation.lineHeight).toPx() + 2.dp.toPx()
                } else 0f
        }
        val second = rows.getOrNull(1)
        recordHeight(
            phoneLyricsCompactHeight(first.size.toFloat(), second?.size?.toFloat(),
                layout.mainAxisItemSpacing.toFloat(), shortHeight(first.contentType == true),
                second?.let { shortHeight(it.contentType == true) }, density.density),
            with(density) { 6.dp.toPx() },
        )
    }

    fun recordHeight(heightPx: Float, softnessPx: Float) {
        measuredHeightPx = heightPx
        measuredSoftnessPx = softnessPx
    }

    // 起步时取一次完整段落高度，中途换句或反向不让路径突然改道。
    fun capture() {
        compactHeightPx = measuredHeightPx
        softnessPx = measuredSoftnessPx
    }
}

internal fun phoneLyricsCompactHeight(first: Float, second: Float?, spacing: Float,
    firstShortHeight: Float, secondShortHeight: Float?, tolerance: Float): Float =
    if (second != null && secondShortHeight != null &&
        first <= firstShortHeight + tolerance && second <= secondShortHeight + tolerance) {
        first + spacing + second
    } else first

/** 页眉尚未让出空间时，完整歌词先在底部停留；接触处以连续速度接入原路径。 */
internal fun phoneLyricsSpatialWindowOffset(travel: Float, lyrics: Float, header: Float,
    compactHeight: Float, softness: Float): Float {
    val p = lyrics.coerceIn(0f, 1f)
    val h = header.coerceIn(0f, 1f)
    val original = travel * (1f - p)
    if (compactHeight <= 0f) return original.roundToInt().toFloat()
    val ahead = ((p - h) * travel - (1f - h) * compactHeight.coerceAtMost(travel)).coerceAtLeast(0f)
    val correction = ahead * ahead / (ahead + softness.coerceAtLeast(1f))
    return (original + correction).roundToInt().toFloat()
}
