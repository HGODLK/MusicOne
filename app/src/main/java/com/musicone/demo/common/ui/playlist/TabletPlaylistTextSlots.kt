package com.musicone.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class TabletPlaylistTextSlots(
    val title: Dp,
    val subtitle: Dp,
    val album: Dp,
    val information: Dp,
    val actions: Dp,
    val more: Dp,
    val description: Dp,
)

internal val LocalTabletPlaylistTextSlots = staticCompositionLocalOf<TabletPlaylistTextSlots?> { null }

/** 用相同字体测量固定行数，详情和溢出按钮迟到时只替换内容，不改变转场落点。 */
@Composable
internal fun rememberTabletPlaylistTextSlots(album: Boolean): TabletPlaylistTextSlots {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val body = MaterialTheme.typography.bodyLarge
    val title = measurer.measure(musicOneEditorialAnnotatedString("歌\n单"),
        body.merge(MusicOneTextStyles.editorialTitle)).size.height
    val subtitle = measurer.measure("作者", body.copy(fontSize = 13.sp, lineHeight = 18.sp)).size.height
    val albumLabel = if (album) measurer.measure("专辑",
        body.copy(fontSize = 20.sp, lineHeight = 26.sp)).size.height else 0
    val playbackLabel = measurer.measure("播放", MaterialTheme.typography.labelLarge.copy(
        fontSize = 16.sp, fontWeight = FontWeight.Bold)).size.height
    val description = measurer.measure("简\n介", body.copy(fontSize = 15.sp, lineHeight = 23.sp)).size.height
    val moreLabel = measurer.measure("更多", MaterialTheme.typography.labelLarge).size.height
    return with(density) {
        val moreHeight = maxOf(48.dp, moreLabel.toDp() + 16.dp)
        TabletPlaylistTextSlots(
            title.toDp(), subtitle.toDp(), albumLabel.toDp(),
            (title + subtitle + albumLabel).toDp() + if (album) 20.dp else 10.dp,
            maxOf(48.dp, playbackLabel.toDp() + 24.dp) + 24.dp,
            moreHeight,
            description.toDp() + moreHeight + 20.dp,
        )
    }
}
