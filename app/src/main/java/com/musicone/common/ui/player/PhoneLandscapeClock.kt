package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import des.c5inco.torph.compose.TextMorph
import des.c5inco.torph.compose.MorphSizeMode
import des.c5inco.torph.compose.MorphClip
import des.c5inco.torph.compose.rememberTextMorphState

/** 时钟只在当前面板可见时每秒更新，不跟随播放进度刷新。 */
@Composable
internal fun PhoneLandscapeClock(visible: Boolean, canvasWidthDp: Float, artwork: PlayerArtworkFrame, modifier: Modifier) {
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var time by remember { mutableStateOf(format.format(Date())) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val referenceSize = 170f * canvasWidthDp / 736f
    val family = remember { FontFamily(Font(R.font.fredoka_medium, FontWeight.Medium)) }
    val ink = rememberPhoneClockInk(artwork)
    val animationClock = rememberPhoneClockFrameClock()
    val clockStyle = LocalTextStyle.current.copy(fontSize = referenceSize.sp,
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum", letterSpacing = (-.025f).em)
    val shadow = remember(ink, density) {
        val blur = with(density) { 3.dp.toPx() }
        val drawShadow: () -> Shadow = { Shadow(ink.shadow.value, Offset.Zero, blur) }
        drawShadow
    }
    // 枚举数字实际宽度，避免替换字体后假设 8 最宽而在整点更新时溢出。
    val fontSize = remember(canvasWidthDp, density, clockStyle, measurer) {
        val digitWidth = ('0'..'9').maxOf { digit ->
            measurer.measure(digit.toString(), clockStyle, maxLines = 1, softWrap = false).size.width
        }
        val colonWidth = measurer.measure(":", clockStyle, maxLines = 1, softWrap = false).size.width
        val mainWidth = digitWidth * 4 + colonWidth
        val secondsWidth = (digitWidth * 2 + colonWidth) * .42f
        val available = with(density) { (canvasWidthDp * .50f).dp.toPx() - 8.dp.toPx() }
        (referenceSize * minOf(1f, available / (mainWidth + secondsWidth).coerceAtLeast(1f)) * .98f).sp
    }
    val mainStyle = clockStyle.copy(fontSize = fontSize)
    val secondsStyle = clockStyle.copy(fontSize = (fontSize.value * .42f).sp)
    val mainText = time.take(5)
    val secondsText = ":" + time.takeLast(2)
    val mainBottomInset = rememberPhoneClockDigitBottomInset(mainStyle)
    val secondsBottomInset = rememberPhoneClockDigitBottomInset(secondsStyle)
    LaunchedEffect(visible) {
        if (visible) while (true) {
            time = format.format(Date())
            delay(1_000L - System.currentTimeMillis().mod(1_000L))
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val snap = !visible || ExperiencePreferences.options.reduceMotion
        Row(verticalAlignment = Alignment.Bottom) {
            PhoneClockDigits(mainText, mainStyle, { ink.primary.value }, shadow,
                snap, animationClock, Modifier.alignBy { it.measuredHeight - mainBottomInset })
            Spacer(Modifier.width(8.dp))
            PhoneClockDigits(secondsText, secondsStyle,
                { ink.secondary.value }, shadow, snap, animationClock,
                Modifier.alignBy { it.measuredHeight - secondsBottomInset })
        }
    }
}

/** 按真实数字墨迹下沿对齐，包含行框留白；只在字体或字号变化时测量。 */
@Composable
private fun rememberPhoneClockDigitBottomInset(style: TextStyle): Int {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val typeface = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All).value
    val measurer = rememberTextMeasurer()
    return remember(style, typeface, density, measurer) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface as android.graphics.Typeface
            textSize = with(density) { style.fontSize.toPx() }
        }
        val bounds = android.graphics.Rect()
        val glyphBottom = (0..9).maxOf { digit ->
            paint.getTextBounds(digit.toString(), 0, 1, bounds)
            bounds.bottom
        }
        val layout = measurer.measure("0123456789", style, maxLines = 1, softWrap = false)
        // 以全部数字的共同下沿为固定基准，保留字形自身下探，换秒不移动整组位置。
        (layout.size.height - layout.firstBaseline - glyphBottom).roundToInt()
    }
}

/** 数字逐位固定占格，冒号独立占位，字体的比例宽度不再移动整组时钟。 */
@Composable
private fun PhoneClockDigits(text: String, style: TextStyle, color: () -> Color, shadow: () -> Shadow, snap: Boolean,
    animationClock: MonotonicFrameClock, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val widths = remember(style, measurer, density) {
        val digit = ('0'..'9').maxOf { measurer.measure(it.toString(), style,
            maxLines = 1, softWrap = false).size.width }
        val colon = measurer.measure(":", style, maxLines = 1, softWrap = false).size.width
        with(density) { digit.toDp() to colon.toDp() }
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        text.forEach { character ->
            Box(Modifier.width(if (character == ':') widths.second else widths.first),
                contentAlignment = Alignment.Center) {
                TextMorph(character.toString(), style = style, drawColor = color, drawShadow = shadow,
                    state = rememberTextMorphState(animationContext = animationClock),
                    // 默认四分之一行溢出会在滚动结束时残留一截；使用上游行框渐隐完整退场。
                    clip = MorphClip.lineBox(), sizeMode = MorphSizeMode.Snap, disabled = snap || character == ':')
            }
        }
    }
}
