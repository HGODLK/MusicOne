package com.musicone.demo

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private data class QqArtworkColorBucket(
    var count: Int = 0,
    var red: Long = 0L,
    var green: Long = 0L,
    var blue: Long = 0L,
    var saturationTotal: Float = 0f,
)

private object QqMusicFeedPaletteCache {
    private val values = object : LruCache<String, List<Color>>(64) {}

    fun get(key: String): List<Color>? = synchronized(values) { values.get(key) }

    fun put(key: String, colors: List<Color>) {
        if (key.isNotBlank() && colors.isNotEmpty()) synchronized(values) { values.put(key, colors) }
    }
}

/** 复用已加载封面提取双色，让歌曲卡从占位色平滑过渡到封面配色。 */
@Composable
internal fun QqMusicFeedArtworkGradient(
    bitmap: Bitmap?,
    cacheKey: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (ExperiencePreferences.options.disableCardColors) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) { content() }
        return
    }
    val colors = rememberQqMusicFeedPalette(bitmap, cacheKey)
    Box(modifier.drawWithCache {
        // 动画颜色只影响绘制缓存，不在组合阶段读取，避免逐帧重组卡片。
        val brush = Brush.horizontalGradient(listOf(colors.first.value, colors.second.value))
        onDrawBehind { drawRect(brush) }
    }) { content() }
}

@Composable
private fun rememberQqMusicFeedPalette(bitmap: Bitmap?, cacheKey: String): Pair<State<Color>, State<Color>> {
    var dominantColors by remember(cacheKey) { mutableStateOf(QqMusicFeedPaletteCache.get(cacheKey)) }
    LaunchedEffect(cacheKey, bitmap) {
        if (bitmap == null || dominantColors != null) return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractQqMusicFeedArtworkColors(bitmap) }
        if (extracted.isNotEmpty()) {
            QqMusicFeedPaletteCache.put(cacheKey, extracted)
            dominantColors = extracted
        }
    }
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val target = remember(dominantColors, surface) { qqMusicFeedGradientColors(dominantColors.orEmpty(), surface) }
    val start = animateColorAsState(target.first, musicMotion(180), label = "qqFeedGradientStart")
    val end = animateColorAsState(target.second, musicMotion(180), label = "qqFeedGradientEnd")
    return remember(start, end) { start to end }
}

internal fun extractQqMusicFeedArtworkColors(bitmap: Bitmap): List<Color> {
    val source = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        else bitmap
    }.getOrDefault(bitmap)
    val stepX = (source.width / 48).coerceAtLeast(1)
    val stepY = (source.height / 27).coerceAtLeast(1)
    val buckets = HashMap<Int, QqArtworkColorBucket>()
    runCatching {
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val pixel = source.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) >= 180) {
                    val red = android.graphics.Color.red(pixel)
                    val green = android.graphics.Color.green(pixel)
                    val blue = android.graphics.Color.blue(pixel)
                    val maximum = maxOf(red, green, blue)
                    val minimum = minOf(red, green, blue)
                    val saturation = if (maximum == 0) 0f else (maximum - minimum) / maximum.toFloat()
                    val brightness = maximum / 255f
                    if (brightness in .10f..0.94f) {
                        val key = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
                        buckets.getOrPut(key, ::QqArtworkColorBucket).apply {
                            count++
                            this.red += red
                            this.green += green
                            this.blue += blue
                            saturationTotal += saturation
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }
    }.getOrElse { return emptyList() }

    val selected = mutableListOf<Color>()
    buckets.values.sortedByDescending { bucket ->
        bucket.count * (1f + bucket.saturationTotal / bucket.count.coerceAtLeast(1) * .55f)
    }.forEach { bucket ->
        if (bucket.count <= 0 || selected.size >= 2) return@forEach
        val candidate = Color(
            (bucket.red / bucket.count).toInt(),
            (bucket.green / bucket.count).toInt(),
            (bucket.blue / bucket.count).toInt(),
        )
        if (selected.none { qqMusicFeedColorDistance(it, candidate) < 64f }) selected += candidate
    }
    val first = selected.firstOrNull() ?: return emptyList()
    if (selected.size < 2) selected += adjacentQqMusicFeedColor(first)
    return selected.take(2)
}

internal fun qqMusicFeedGradientColors(colors: List<Color>, surface: Color): Pair<Color, Color> {
    if (colors.isEmpty()) return surface to surface
    val darkTheme = surface.luminance() < .22f
    val first = readableQqMusicFeedColor(colors.first(), surface, darkTheme)
    val second = colors.getOrNull(1) ?: adjacentQqMusicFeedColor(colors.first())
    return first to readableQqMusicFeedColor(second, surface, darkTheme)
}

private fun readableQqMusicFeedColor(color: Color, surface: Color, darkTheme: Boolean): Color {
    var result = lerp(color, surface, if (darkTheme) .68f else .44f)
    if (darkTheme) {
        while (result.luminance() > .13f) result = lerp(result, surface, .18f)
        while (result.luminance() < .038f) result = lerp(result, Color.White, .05f)
    } else {
        while (result.luminance() < .26f) result = lerp(result, Color.White, .12f)
    }
    return result
}

private fun adjacentQqMusicFeedColor(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + 24f) % 360f
    hsv[1] = hsv[1].coerceIn(.28f, .82f)
    hsv[2] = hsv[2].coerceIn(.34f, .88f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun qqMusicFeedColorDistance(first: Color, second: Color): Float {
    val red = (first.red - second.red) * 255f
    val green = (first.green - second.green) * 255f
    val blue = (first.blue - second.blue) * 255f
    return sqrt(red * red + green * green + blue * blue)
}
