package com.musicone.demo

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal data class AtmosphereBlobMotion(
    val baseX: Float,
    val baseY: Float,
    val travelX: Float,
    val travelY: Float,
    val diameter: Float,
    val frequencyX: Double,
    val frequencyY: Double,
    val phaseX: Double,
    val phaseY: Double,
)

/** 九个采样色保持均匀覆盖，整段播放会话共用路径，切歌只改变颜色而不改变位置。 */
internal fun atmosphereBlobMotions(): List<AtmosphereBlobMotion> {
    val random = Random(ATMOSPHERE_MOTION_SEED)
    val cells = (0 until 9).shuffled(random)
    return List(9) { index ->
        val cell = cells[index]
        val column = cell % 3
        val row = cell / 3
        AtmosphereBlobMotion(
            baseX = (column + .5f) / 3f + (random.nextFloat() - .5f) * .12f,
            baseY = (row + .5f) / 3f + (random.nextFloat() - .5f) * .12f,
            travelX = .08f + random.nextFloat() * .08f,
            travelY = .07f + random.nextFloat() * .07f,
            diameter = .72f + random.nextFloat() * .22f,
            frequencyX = .34 + random.nextDouble() * .28,
            frequencyY = .31 + random.nextDouble() * .25,
            phaseX = random.nextDouble() * Math.PI * 2.0,
            phaseY = random.nextDouble() * Math.PI * 2.0,
        )
    }
}

internal fun atmosphereBlobCenter(
    motion: AtmosphereBlobMotion,
    phase: Double,
    width: Float,
    height: Float,
): Offset = Offset(
    x = width * (motion.baseX + sin(phase * motion.frequencyX + motion.phaseX).toFloat() * motion.travelX +
        sin(phase * .17 + motion.phaseY).toFloat() * motion.travelX * .24f),
    y = height * (motion.baseY + cos(phase * motion.frequencyY + motion.phaseY).toFloat() * motion.travelY +
        cos(phase * .13 + motion.phaseX).toFloat() * motion.travelY * .22f),
)

@Composable
internal fun ArtworkAtmosphereBlobs(
    fromColors: IntArray,
    toColors: IntArray,
    transitionProgress: () -> Float,
    phaseState: PlayerAtmosphereMotionState,
    modifier: Modifier = Modifier,
) {
    val blob = remember { softArtworkBlob() }
    val motions = remember { atmosphereBlobMotions() }
    val fromBase = remember(fromColors.contentHashCode()) { averageArtworkColor(fromColors) }
    val toBase = remember(toColors.contentHashCode()) { averageArtworkColor(toColors) }
    Box(modifier.fillMaxSize().drawWithCache {
        val longEdge = maxOf(size.width, size.height)
        val sides = IntArray(motions.size) { index ->
            (longEdge * motions[index].diameter).roundToInt().coerceAtLeast(1)
        }
        val starts = Array(motions.size) { index ->
            Color(fromColors.getOrElse(index) { fromColors.lastOrNull() ?: AndroidColor.BLACK })
        }
        val ends = Array(motions.size) { index ->
            Color(toColors.getOrElse(index) { toColors.lastOrNull() ?: AndroidColor.BLACK })
        }
        val filters = arrayOfNulls<ColorFilter>(motions.size)
        var cachedProgress = Float.NaN
        var base = fromBase
        onDrawBehind {
            val progress = transitionProgress().coerceIn(0f, 1f)
            // 相位只改变位置；颜色交接进度不变时复用滤镜，仍逐帧绘制全部九个色团。
            if (cachedProgress != progress) {
                base = lerp(fromBase, toBase, progress)
                for (index in filters.indices) {
                    filters[index] = ColorFilter.tint(lerp(starts[index], ends[index], progress))
                }
                cachedProgress = progress
            }
            drawRect(base)
            val phase = phaseState.phase
            motions.forEachIndexed { index, motion ->
                val center = atmosphereBlobCenter(motion, phase, size.width, size.height)
                val side = sides[index]
                drawImage(
                    image = blob,
                    dstOffset = IntOffset(
                        (center.x - side / 2f).roundToInt(),
                        (center.y - side / 2f).roundToInt(),
                    ),
                    dstSize = IntSize(side, side),
                    alpha = .62f,
                    colorFilter = filters[index],
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    })
}

private fun softArtworkBlob(): ImageBitmap {
    val side = 96
    val radius = side / 2f
    val bitmap = createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            radius,
            radius,
            radius,
            intArrayOf(AndroidColor.WHITE, AndroidColor.WHITE, AndroidColor.TRANSPARENT),
            floatArrayOf(0f, .28f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    AndroidCanvas(bitmap).drawCircle(radius, radius, radius, paint)
    return bitmap.asImageBitmap()
}

private const val ATMOSPHERE_MOTION_SEED = 0x4D31_5839

private fun averageArtworkColor(colors: IntArray): Color {
    if (colors.isEmpty()) return Color.Black
    var red = 0L
    var green = 0L
    var blue = 0L
    colors.forEach { color ->
        red += AndroidColor.red(color)
        green += AndroidColor.green(color)
        blue += AndroidColor.blue(color)
    }
    return Color(
        red = red.toFloat() / colors.size / 255f,
        green = green.toFloat() / colors.size / 255f,
        blue = blue.toFloat() / colors.size / 255f,
        alpha = 1f,
    )
}
