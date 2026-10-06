package com.musicone.demo

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
    Layout(
        modifier = modifier,
        content = {
            Text(musicOneUiAnnotatedString(text), style = style.copy(fontSize = fontSize),
                color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) { measurables, constraints ->
        // 用 sp 换算，跟随系统字体缩放；字体回退只影响字形，不改变两行的基线间距。
        val scale = fontSize.value / if (artist) 12f else 14f
        val height = constraints.constrainHeight((style.lineHeight.value * scale).sp.roundToPx())
        val baseline = ((if (artist) 12.5f else 15.5f) * scale).sp.roundToPx()
        val textLayout = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val textBaseline = textLayout[FirstBaseline]
        layout(textLayout.width, height) {
            textLayout.placeRelative(0, baseline - textBaseline)
        }
    }
}
