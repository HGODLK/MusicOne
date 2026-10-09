package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun ImmersiveLyricLine(
    line: TimedLyric,
    index: Int,
    current: Int,
    largeText: Boolean,
    active: Boolean,
    animateEmphasis: Boolean = true,
    settleProgress: () -> Float = { 1f },
    playbackMotion: LyricPlaybackStepMotion,
    playbackStepActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutSize = LocalPlayerLayoutSpec.current.lyricBaseSize
    val ink = LocalContentColor.current
    val activeSize = layoutSize
    val upcomingSize = if (largeText) 40f else MusicOneTextStyles.lyricUpcoming.fontSize.value
    val activeLineHeight = if (largeText) 56f else MusicOneTextStyles.lyricActive.lineHeight.value
    val translationSize = if (largeText) 14f else MusicOneTextStyles.lyricTranslation.fontSize.value
    val translationLineHeight = if (largeText) 20f else MusicOneTextStyles.lyricTranslation.lineHeight.value
    val row = remember(playbackMotion, index) { playbackMotion.row(index) }
    DisposableEffect(playbackMotion, index) {
        playbackMotion.retain(index, row)
        onDispose { playbackMotion.release(index, row) }
    }
    SideEffect { playbackMotion.emphasize(index, current, animateEmphasis, playbackStepActive) }
    val density = LocalDensity.current
    // 按四分之一 dp 复用歌词行模糊；顶部和底部的玻璃遮罩仍由独立图层负责。
    val effects = remember(density.density) { lyricBlurEffects(density.density) }
    // 点击区域保持整行；字号和焦点动画不改变换行与行高。
    Box(modifier.fillMaxWidth().clickable(enabled = active, onClick = onClick).padding(vertical = 5.dp)) {
        Column(Modifier.graphicsLayer {
                val settle = if (index == current) settleProgress().coerceIn(0f, 1f) else 1f
                val upcomingScale = upcomingSize / activeSize
                val scale = upcomingScale + (1f - upcomingScale) * row.focus.value
                transformOrigin = TransformOrigin(0f, .5f)
                scaleX = scale * (.94f + .06f * settle)
                scaleY = scale * (.94f + .06f * settle)
                alpha = row.opacity.value * (.72f + .28f * settle)
                translationY = playbackMotion.offsetPx(index)
                val blurDp = maxOf(row.blur.value, (1f - settle) * 2f)
                val lyricBlurDisabled = ExperiencePreferences.options.disableBlur ||
                    ExperiencePreferences.options.disableLyricBlur
                renderEffect = if (lyricBlurDisabled) null else effects[(blurDp * 4f).roundToInt().coerceIn(0, 8)]
            }) {
            Text(
                musicOneDisplayAnnotatedString(line.text),
                style = MusicOneTextStyles.lyricActive.copy(
                    fontSize = activeSize.sp,
                    lineHeight = activeLineHeight.sp,
                ),
                color = ink,
            )
            line.translation?.let { translation ->
                Text(
                    musicOneUiAnnotatedString(translation),
                    style = MusicOneTextStyles.lyricTranslation.copy(
                        fontSize = translationSize.sp,
                        lineHeight = translationLineHeight.sp,
                    ),
                    color = ink.copy(alpha = .72f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// 效果对象不含歌词内容，同密度下跨行及新旧歌词复用。
private val lyricEffectCache = object : LinkedHashMap<Float, List<BlurEffect?>>(4, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Float, List<BlurEffect?>>?): Boolean = size > 4
}

private fun lyricBlurEffects(density: Float): List<BlurEffect?> = synchronized(lyricEffectCache) {
    lyricEffectCache.getOrPut(density) {
        List(9) { step ->
            if (step == 0 || android.os.Build.VERSION.SDK_INT < 31) null
            else BlurEffect(step * .25f * density, step * .25f * density, TileMode.Clamp)
        }
    }
}
