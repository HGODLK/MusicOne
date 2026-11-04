package com.musicone

import android.graphics.Color as AndroidColor
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.Animatable as ColorAnimation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class PhoneClockInk(val primary: Color, val secondary: Color, val shadow: Color)

internal class PhoneClockInkAnimation(initial: PhoneClockInk) {
    val primary: ColorAnimation<Color, AnimationVector4D> = Animatable(initial.primary)
    val secondary: ColorAnimation<Color, AnimationVector4D> = Animatable(initial.secondary)
    val shadow: ColorAnimation<Color, AnimationVector4D> = Animatable(initial.shadow)
}

private data class ClockBackdropRange(val darkest: Color, val brightest: Color)

/** 共用已解码封面和九色缓存，不增加图片请求或屏幕回读。 */
@Composable
internal fun rememberPhoneClockInk(frame: PlayerArtworkFrame): PhoneClockInkAnimation {
    val colors = remember(frame) {
        ArtworkColorFieldRepository.peek(frame.identity)
            ?: ArtworkColorFieldRepository.prepare(frame.identity, frame.bitmap)
            ?: ArtworkColorSampler.placeholder(frame.identity)
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val customBackdrop = LocalSourceBackdropVisual.current != null
    val range = remember(colors, dark, customBackdrop) {
        if (customBackdrop || colors.any { AndroidColor.alpha(it) < 255 }) {
            ClockBackdropRange(Color.Black, Color.White)
        }
        else {
            // 稳态色团叠加在各通道极值之内；计入现有物理屏幕渐变阴影的最浅、最深值。
            val samples = colors.map { Color(it).copy(alpha = 1f) }
            ClockBackdropRange(
                Color(samples.minOf { it.red }, samples.minOf { it.green }, samples.minOf { it.blue })
                    .dimmed(if (dark) .30f else .49f),
                Color(samples.maxOf { it.red }, samples.maxOf { it.green }, samples.maxOf { it.blue })
                    .dimmed(if (dark) .50f else .70f),
            )
        }
    }
    val target = remember(colors, range) { clockInk(colors, range) }
    // 首次直接使用目标配色；后续切歌沿用背景色场的九百毫秒曲线，中断时从当前颜色续接。
    val animation = remember { PhoneClockInkAnimation(target) }
    LaunchedEffect(target) {
        coroutineScope {
            launch {
                if (ExperiencePreferences.options.reduceMotion) animation.primary.snapTo(target.primary)
                else animation.primary.animateTo(target.primary, musicMotion(900))
            }
            launch {
                if (ExperiencePreferences.options.reduceMotion) animation.secondary.snapTo(target.secondary)
                else animation.secondary.animateTo(target.secondary, musicMotion(900))
            }
            launch {
                if (ExperiencePreferences.options.reduceMotion) animation.shadow.snapTo(target.shadow)
                else animation.shadow.animateTo(target.shadow, musicMotion(900))
            }
        }
    }
    return animation
}

private fun clockInk(colors: IntArray, background: ClockBackdropRange): PhoneClockInk {
    val hue = FloatArray(3)
    val weights = FloatArray(12)
    colors.forEach { color ->
        AndroidColor.colorToHSV(color, hue)
        if (hue[1] >= .12f && hue[2] >= .12f) {
            weights[(hue[0] / 30f).toInt().coerceIn(0, 11)] += hue[1] * hue[2]
        }
    }
    val bucket = weights.indices.maxBy { weights[it] }
    val accent = colors.filter { color ->
        AndroidColor.colorToHSV(color, hue)
        (hue[0] / 30f).toInt().coerceIn(0, 11) == bucket && hue[1] >= .12f && hue[2] >= .12f
    }.maxByOrNull { color ->
        AndroidColor.colorToHSV(color, hue)
        hue[1] * hue[2]
    }
    // 黑白封面没有有效色相时使用中性色，不凭空生成彩色。
    val tint = if (accent == null) Color.White else {
        AndroidColor.colorToHSV(accent, hue)
        Color(AndroidColor.HSVToColor(floatArrayOf(hue[0], hue[1].coerceIn(.28f, .50f), 1f)))
    }
    repeat(21) { step ->
        val candidate = lerp(tint, Color.White, step / 20f)
        if (candidate.luminance() >= background.brightest.luminance() &&
            clockContrast(candidate, background.brightest) >= CLOCK_MIN_CONTRAST) {
            return readableClockInk(candidate, true)
        }
    }
    repeat(21) { step ->
        val candidate = lerp(tint, Color.Black, step / 20f)
        if (candidate.luminance() <= background.darkest.luminance() &&
            clockContrast(candidate, background.darkest) >= CLOCK_MIN_CONTRAST) {
            return readableClockInk(candidate, false)
        }
    }
    // 不再绘制背景保护圆；混合背景选最清晰的浅／深色，仅保留细微字形阴影。
    val light = lerp(tint, Color.White, .90f)
    val dark = lerp(tint, Color.Black, .90f)
    fun readability(color: Color): Float {
        val luminance = color.luminance()
        if (luminance in background.darkest.luminance()..background.brightest.luminance()) return 1f
        return minOf(clockContrast(color, background.darkest), clockContrast(color, background.brightest))
    }
    return if (readability(light) >= readability(dark)) readableClockInk(light, true)
        else readableClockInk(dark, false)
}

private fun readableClockInk(primary: Color, light: Boolean) = PhoneClockInk(
    primary, lerp(primary, if (light) Color.White else Color.Black, .15f),
    if (light) Color.Black.copy(alpha = .40f) else Color.White.copy(alpha = .25f),
)

private fun Color.dimmed(factor: Float) = Color(red * factor, green * factor, blue * factor)

private fun clockContrast(a: Color, b: Color): Float =
    (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

private const val CLOCK_MIN_CONTRAST = 4.5f
