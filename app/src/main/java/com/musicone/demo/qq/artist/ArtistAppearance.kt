package com.musicone.demo

import android.graphics.Bitmap
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class ArtistAppearance(val background: Color, val accent: Color) {
    val colors get() = darkColorScheme(primary = accent, onPrimary = background,
        background = background, surface = background, onBackground = Color(0xFFF5F5F5),
        onSurface = Color(0xFFF5F5F5), onSurfaceVariant = Color(0xFFC7CCCA))
}

private val DefaultArtistAppearance = ArtistAppearance(Color(0xFF283431), Color(0xFFD9ECE4))

/** 取色与图片地址绑定，避免新地址读取到上一张尚未替换的位图。 */
@Composable
internal fun rememberArtistAppearance(photos: List<String>, pager: PagerState): ArtistAppearance {
    val palettes = remember(photos) { mutableStateMapOf<String, ArtistAppearance>() }
    LaunchedEffect(photos, pager.currentPage) {
        ((pager.currentPage - 1)..(pager.currentPage + 1)).mapNotNull(photos::getOrNull).forEach { url ->
            if (url !in palettes) launch {
                val bitmap = ArtworkRepository.load(url) ?: return@launch
                palettes[url] = withContext(Dispatchers.Default) { artistAppearanceFromBitmap(bitmap) }
            }
        }
    }
    val page = pager.currentPage
    val offset = pager.currentPageOffsetFraction
    val from = palettes[photos.getOrNull(page)] ?: DefaultArtistAppearance
    val to = palettes[photos.getOrNull(page + if (offset < 0) -1 else 1)] ?: from
    // 横向翻页直接混色；首次取色一次生效，避免颜色动画逐帧重组整棵歌手页。
    return ArtistAppearance(
        background = lerp(from.background, to.background, abs(offset)),
        accent = lerp(from.accent, to.accent, abs(offset)),
    )
}

/** 从照片主体提取主色，底色保留色相而非统一压黑；低饱和照片使用冷蓝强调。 */
internal fun artistAppearanceFromBitmap(bitmap: Bitmap): ArtistAppearance {
    val buckets = HashMap<Int, Pair<Float, Int>>()
    val hsv = FloatArray(3)
    for (y in 0 until 24) for (x in 0 until 24) {
        val pixel = bitmap.getPixel(x * bitmap.width / 24, y * bitmap.height / 24)
        android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[2] < .08f || hsv[2] > .96f) continue
        val key = ((hsv[0] / 20).toInt() shl 4) + (hsv[1] * 3).toInt()
        val weight = .3f + hsv[1]
        val previous = buckets[key]
        buckets[key] = (previous?.first.orZero() + weight) to (previous?.second ?: pixel)
    }
    val dominant = buckets.maxByOrNull { it.value.first }?.value?.second ?: return DefaultArtistAppearance
    android.graphics.Color.colorToHSV(dominant, hsv)
    val hue = if (hsv[1] < .14f) 200f else hsv[0]
    val saturation = hsv[1]
    val background = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue,
        (saturation * .7f).coerceIn(.12f, .58f), if (saturation < .14f) .14f else .36f)))
    val accent = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, .23f, 1f)))
    return ArtistAppearance(background, accent)
}

private fun Float?.orZero() = this ?: 0f
