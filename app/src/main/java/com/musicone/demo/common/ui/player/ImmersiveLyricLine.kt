package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun ImmersiveLyricLine(
    line: TimedLyric,
    index: Int,
    current: Int,
    typography: LyricTypography,
    active: Boolean,
    animateEmphasis: Boolean = true,
    settleProgress: () -> Float = { 1f },
    playbackMotion: LyricPlaybackStepMotion,
    playbackStepActive: Boolean = false,
    smoothProgressMs: (() -> Long)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    boundary: LyricLineBoundary? = null,
) {
    val ink = LocalContentColor.current
    val systemDensity = LocalDensity.current
    val lyricDensity = remember(systemDensity.density) { lyricDisplayDensity(systemDensity) }
    val activeSize = typography.activeSp
    val activeLineHeight = typography.activeLineSp
    val translationSize = typography.translationSp
    val translationLineHeight = typography.translationLineSp
    val row = remember(playbackMotion, index) { playbackMotion.row(index) }
    DisposableEffect(playbackMotion, index) {
        playbackMotion.retain(index, row)
        onDispose { playbackMotion.release(index, row) }
    }
    SideEffect { playbackMotion.emphasize(index, current, animateEmphasis, playbackStepActive) }
    val density = lyricDensity
    // 按四分之一 dp 复用歌词行模糊；顶部和底部的玻璃遮罩仍由独立图层负责。
    val effects = remember(density.density) { lyricBlurEffects(density.density) }

    val isCurrentOrRecent = index in (current - 1)..(current + 1)
    val verbatimEnabled = ExperiencePreferences.options.verbatimLyricAnimation
    val canAnimateVerbatim = verbatimEnabled && line.words.isNotEmpty() && smoothProgressMs != null && isCurrentOrRecent
    val baseAnnotated = remember(line.text) { lyricOriginalAnnotatedString(line.text) }
    val highlightRanges = remember(line.text, line.words) { lyricWordHighlightRanges(line) }
    SideEffect {
        playbackMotion.configureWords(index, highlightRanges, smoothProgressMs,
            canAnimateVerbatim, index == current, animateEmphasis && !ExperiencePreferences.options.reduceMotion,
            allowTail = playbackStepActive && index == current - 1)
    }
    val textLayout = remember(line.text, typography) { mutableStateOf<TextLayoutResult?>(null) }
    val translationLayout = remember(line.translation, typography) { mutableStateOf<TextLayoutResult?>(null) }
    val readTextLayout = remember(textLayout) { { textLayout.value } }
    val readTranslationLayout = remember(translationLayout) { { translationLayout.value } }
    val updateTextLayout = remember(textLayout) { { result: TextLayoutResult -> textLayout.value = result } }
    val updateTranslationLayout = remember(translationLayout) { { result: TextLayoutResult -> translationLayout.value = result } }
    val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val readRowCoordinates = remember(rowCoordinates) { { rowCoordinates[0] } }

    // 额外扩展模糊采样范围；正文仍保留原有 8dp 边距、换行宽度和缩放原点。
    CompositionLocalProvider(LocalDensity provides lyricDensity) {
        Layout(
            content = {
                // 只移除图层里的横向空白，换行上限、外伸留白和整行触摸范围保持原尺寸。
                Column(Modifier.wrapContentWidth(Alignment.Start).graphicsLayer {
                val settle = if (index == current) settleProgress().coerceIn(0f, 1f) else 1f
                // 未播放句缩小至 0.925，当前及已播放句保留原字号（1.0），逐句接续不改变排版。
                val scale = .925f + .075f * row.played.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(8.dp.toPx() / size.width.coerceAtLeast(1f), .5f)
                scaleX = scale * (.94f + .06f * settle)
                scaleY = scale * (.94f + .06f * settle)
                alpha = row.opacity.value * (.72f + .28f * settle)
                translationY = playbackMotion.offsetPx(index)
                val blurDp = maxOf(row.blur.value, (1f - settle) * 2f)
                val lyricBlurDisabled = ExperiencePreferences.options.disableBlur ||
                    ExperiencePreferences.options.disableLyricBlur
                renderEffect = if (lyricBlurDisabled) null else effects[(blurDp * 4f).roundToInt().coerceIn(0, 8)]
                }.padding(horizontal = 16.dp)) {
                    Text(
                        baseAnnotated,
                        onTextLayout = updateTextLayout,
                        style = MusicOneTextStyles.lyricActive.copy(
                            fontSize = activeSize.sp,
                            lineHeight = activeLineHeight.sp,
                        ),
                        color = ink,
                        modifier = Modifier.lyricTextBoundary(boundary, readTextLayout, readRowCoordinates)
                            .lyricWordAlphaDrawing(
                            ranges = highlightRanges,
                            textLayout = readTextLayout,
                            motion = row.words,
                            color = ink,
                        ),
                    )
                    line.translation?.let { translation ->
                        Text(
                            musicOneUiAnnotatedString(translation),
                            onTextLayout = updateTranslationLayout,
                            style = MusicOneTextStyles.lyricTranslation.copy(
                                fontSize = translationSize.sp,
                                lineHeight = translationLineHeight.sp,
                            ),
                            color = ink.copy(alpha = .72f),
                            modifier = Modifier.padding(top = 2.dp)
                                .lyricTextBoundary(boundary, readTranslationLayout, readRowCoordinates),
                        )
                    }
                }
            },
            modifier = modifier.fillMaxWidth().onGloballyPositioned {
                rowCoordinates[0] = it
                if (index == current) lyricTracePosition("MusicOne:lyric-row-y") { it.positionInRoot().y }
            }
                .clickable(enabled = active, onClick = onClick).padding(vertical = 5.dp),
        ) { children, constraints ->
            val overflow = 8.dp.roundToPx()
            val width = constraints.maxWidth.coerceAtLeast(1)
            val layerWidth = (width + overflow * 2).coerceAtMost(Int.MAX_VALUE)
            val child = children.single().measure(constraints.copy(minWidth = layerWidth, maxWidth = layerWidth))
            layout(width, child.height) { child.place(-overflow, 0) }
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
