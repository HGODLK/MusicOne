package com.musicone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    freezeVisual: Boolean = false,
    holdWords: Boolean = false,
    layoutProof: LyricLayoutProof? = null,
    readingAnchor: PhoneLyricsReadingAnchor? = null,
    readingPane: LyricWindowPane? = null,
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
    SideEffect { if (!freezeVisual) playbackMotion.emphasize(index, current, animateEmphasis, playbackStepActive) }
    val density = lyricDensity
    val blurEffects = remember(density.density) { lyricBlurEffects(density.density) }

    val isCurrentOrRecent = index in (current - 1)..(current + 1)
    val verbatimEnabled = ExperiencePreferences.options.verbatimLyricAnimation
    val pageMoving = LocalPlayerLyricsMoving.current
    val preparingTurn = LocalPhonePlayerTurn.current?.preparing == true
    val canAnimateVerbatim = verbatimEnabled && line.words.isNotEmpty() && smoothProgressMs != null && isCurrentOrRecent
    val fontMode = ExperiencePreferences.options.fontMode
    val baseAnnotated = remember(line.text, fontMode) { lyricOriginalAnnotatedString(line.text, fontMode) }
    val highlightRanges = remember(line.text, line.words) { lyricWordHighlightRanges(line) }
    SideEffect {
        if (holdWords && row.words.configured) playbackMotion.holdWords(index)
        else if (!freezeVisual || !row.words.configured) playbackMotion.configureWords(index, highlightRanges, smoothProgressMs,
            canAnimateVerbatim, index == current, animateEmphasis && !ExperiencePreferences.options.reduceMotion,
            allowTail = playbackStepActive && index == current - 1)
    }
    val textLayout = remember(line.text, typography, fontMode) { mutableStateOf<TextLayoutResult?>(null) }
    val translationLayout = remember(line.translation, typography, fontMode) { mutableStateOf<TextLayoutResult?>(null) }
    val paragraphWidth = remember { intArrayOf(0) }
    val readTextLayout = remember(textLayout) { { textLayout.value } }
    val readTranslationLayout = remember(translationLayout) { { translationLayout.value } }
    val updateTextLayout = remember(textLayout) { { result: TextLayoutResult -> textLayout.value = result } }
    val updateTranslationLayout = remember(translationLayout) { { result: TextLayoutResult -> translationLayout.value = result } }
    val rowCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val readRowCoordinates = remember(rowCoordinates) { { rowCoordinates.value } }
    val latestCurrent by rememberUpdatedState(current)
    val latestSettle by rememberUpdatedState(settleProgress)
    val visualScale = remember(row, index) { {
        val settle = if (index == latestCurrent) latestSettle().coerceIn(0f, 1f) else 1f
        (.925f + .075f * row.played.value.coerceIn(0f, 1f)) * (.94f + .06f * settle)
    } }
    val boundaryPivotX = with(density) { 8.dp.toPx() - 8.dp.roundToPx() }
    val boundaryTransform = remember(row, playbackMotion, index, boundaryPivotX) {
        LyricLineVisualTransform(pivot = { Offset(boundaryPivotX, (rowCoordinates.value?.size?.height ?: 0) / 2f) },
            scale = visualScale, offset = { playbackMotion.offsetPx(index) })
    }

    // 额外扩展模糊采样范围；正文仍保留原有 8dp 边距、换行宽度和缩放原点。
    CompositionLocalProvider(LocalDensity provides lyricDensity) {
        Layout(
            content = {
                // 只移除图层里的横向空白，换行上限、外伸留白和整行触摸范围保持原尺寸。
                Column(Modifier.wrapContentWidth(Alignment.Start).graphicsLayer {
                val settle = if (index == current) settleProgress().coerceIn(0f, 1f) else 1f
                // 未播放句缩小至 0.925，当前及已播放句保留原字号（1.0），逐句接续不改变排版。
                transformOrigin = TransformOrigin(8.dp.toPx() / size.width.coerceAtLeast(1f), .5f)
                scaleX = visualScale()
                scaleY = scaleX
                PlayerMotionDiagnostics.sample("lyric-ink/$index") {
                    "index=$index current=$current rowAlpha=${row.opacity.value} alpha=${row.opacity.value * (.72f + .28f * settle)} " +
                        "wordStrength=${row.words.sweepStrength} boundary=${boundary != null}"
                }
                translationY = playbackMotion.offsetPx(index)
                // 透明度和模糊都是图层属性，切句时只更新属性，不重绘这一行。
                alpha = row.opacity.value * (.72f + .28f * settle)
                renderEffect = blurEffects[lyricRowBlurStep(maxOf(row.blur.value, (1f - settle) * 2f))]
                }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        baseAnnotated,
                        onTextLayout = updateTextLayout,
                        style = MusicOneTextStyles.lyricActive.copy(
                            fontSize = activeSize.sp,
                            lineHeight = activeLineHeight.sp,
                        ),
                        color = ink,
                        modifier = Modifier.phoneLyricsReadingRow(readingAnchor, readingPane, index, readTextLayout)
                            .lyricTextBoundary(boundary, readTextLayout, readRowCoordinates,
                                prepare = layoutProof != null, transform = boundaryTransform,
                                contentKey = Triple(textLayout.value, highlightRanges, ink))
                            .lyricLayoutProof(layoutProof, index, false, readTextLayout, readTranslationLayout) { paragraphWidth[0] }
                            .lyricWordAlphaDrawing(
                            ranges = highlightRanges,
                            textLayout = readTextLayout,
                            motion = row.words,
                            color = ink,
                            // 滚动状态放最后读，只有待预备的那一行订阅，其他行不随滚动起止重组。
                            prepareAhead = index == current + 2 && !freezeVisual && !pageMoving && verbatimEnabled &&
                                smoothProgressMs != null && readingPane?.listState?.isScrollInProgress != true,
                            prepareForTurn = preparingTurn && layoutProof != null && verbatimEnabled &&
                                smoothProgressMs != null && index in (current - 1)..(current + 2),
                        ),
                    )
                    line.translation?.let { translation ->
                        Text(
                            remember(translation, fontMode) { musicOneUiAnnotatedString(translation, mode = fontMode) },
                            onTextLayout = updateTranslationLayout,
                            style = MusicOneTextStyles.lyricTranslation.copy(
                                fontSize = translationSize.sp,
                                lineHeight = translationLineHeight.sp,
                            ),
                            color = ink.copy(alpha = .72f),
                            modifier = Modifier.padding(top = 2.dp)
                                .lyricTextBoundary(boundary, readTranslationLayout, readRowCoordinates,
                                    prepare = layoutProof != null, transform = boundaryTransform,
                                    contentKey = translationLayout.value to ink)
                                .lyricLayoutProof(layoutProof, index, true, readTranslationLayout, readTextLayout) { paragraphWidth[0] },
                        )
                    }
                }
            },
            modifier = modifier.fillMaxWidth().onGloballyPositioned {
                rowCoordinates.value = it
                if (index == current) lyricTracePosition("MusicOne:lyric-row-y") { it.positionInRoot().y }
            }
                .clickable(enabled = active, onClick = onClick).padding(vertical = 5.dp),
        ) { children, constraints ->
            val overflow = 8.dp.roundToPx()
            val width = constraints.maxWidth.coerceAtLeast(1)
            val layerWidth = (width + overflow * 2).coerceAtMost(Int.MAX_VALUE)
            paragraphWidth[0] = (layerWidth - 16.dp.roundToPx() * 2).coerceAtLeast(0)
            val child = children.single().measure(constraints.copy(minWidth = layerWidth, maxWidth = layerWidth))
            val verticalOverflow = 6.dp.roundToPx()
            layout(width, (child.height - verticalOverflow * 2).coerceAtLeast(0)) {
                child.place(-overflow, -verticalOverflow)
            }
        }
    }
}
