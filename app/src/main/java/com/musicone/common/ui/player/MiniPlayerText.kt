package com.musicone

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.sp

/** 迷你播放器与飞行层共用固定行框、基线，切歌淡变不随文字重新定位。 */
@Composable
internal fun MiniPlayerText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    artist: Boolean = false,
    fontSize: TextUnit = if (artist) 12.sp else 14.sp,
) {
    val style = if (artist) MusicOneTextStyles.miniPlayerArtist else MusicOneTextStyles.miniPlayerTitle
    val arabic = containsArabicOrPersian(text)
    Layout(
        modifier = modifier,
        content = {
            Text(musicOneUiAnnotatedString(text),
                style = style.copy(fontSize = fontSize, textDirection = contentTextDirection(text)),
                color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) { measurables, constraints ->
        // 用 sp 换算，跟随系统字体缩放；阿拉伯／波斯略抬基线，给下方点与弯钩留足空间而不改总行高。
        val scale = fontSize.value / if (artist) 12f else 14f
        val height = constraints.constrainHeight((style.lineHeight.value * scale).sp.roundToPx())
        val baseLineSp = if (artist) (if (arabic) 11.8f else 12.5f) else (if (arabic) 14.8f else 15.5f)
        val baseline = (baseLineSp * scale).sp.roundToPx()
        val textLayout = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val textBaseline = textLayout[FirstBaseline]
        layout(textLayout.width, height) {
            textLayout.placeRelative(0, baseline - textBaseline)
        }
    }
}
