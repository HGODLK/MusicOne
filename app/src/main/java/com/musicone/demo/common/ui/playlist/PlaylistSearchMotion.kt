package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 关闭搜索后短暂保留列表动效，让恢复的歌曲完成淡入和位置交接。 */
@Composable
internal fun rememberPlaylistSearchMotionActive(expanded: Boolean): Boolean {
    var active by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            active = true
        } else {
            delay(if (ExperiencePreferences.options.reduceMotion) 0L else 320L)
            active = false
        }
    }
    return active
}

internal fun playlistSearchResultsBottomPadding(
    bottomInset: Dp,
    imeInset: Dp,
    expanded: Boolean,
): Dp = if (expanded) {
    maxOf(bottomInset, imeInset) + 84.dp
} else {
    bottomInset + 24.dp
}

internal fun playlistSearchHasQuery(query: String): Boolean = query.isNotBlank()

internal fun playlistSearchCoverScrollOffset(coverSizePx: Int, visibleCoverPx: Int): Int =
    (coverSizePx - visibleCoverPx).coerceAtLeast(0)
