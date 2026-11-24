package com.musicone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

@Stable
internal class PlaylistSearchState {
    var expanded by mutableStateOf(false)
    var query by mutableStateOf("")
    var coverHeightPx by mutableIntStateOf(0)

    fun open() { expanded = true }

    fun close() {
        expanded = false
        query = ""
    }
}

/**
 * 搜索的展开与输入只在页面存活期间保留，不随页面状态保存：
 * 滑动手势直接退出时不经过“先收起搜索”的返回逻辑，保存后重新进入会恢复成输入状态。
 */
@Composable
internal fun rememberPlaylistSearchState(playlistId: String): PlaylistSearchState =
    remember(playlistId) { PlaylistSearchState() }

internal fun List<MusicTrack>.matchingPlaylistQuery(query: String): List<MusicTrack> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    return filter { track ->
        track.title.contains(keyword, ignoreCase = true) ||
            track.artists.contains(keyword, ignoreCase = true)
    }
}

internal fun playlistTrackItemIndex(wide: Boolean, trackIndex: Int, extraHeaderItems: Int = 0): Int =
    trackIndex + (if (wide) 1 else 3) + extraHeaderItems

internal suspend fun LazyListState.locatePlaylistTrack(wide: Boolean, trackIndex: Int,
    extraHeaderItems: Int = 0) {
    val itemIndex = playlistTrackItemIndex(wide, trackIndex, extraHeaderItems)
    if (abs(firstVisibleItemIndex - itemIndex) > 18) {
        scrollToItem(itemIndex)
    }
    val itemSize = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }?.size
        ?: layoutInfo.visibleItemsInfo.firstOrNull()?.size
        ?: 0
    val centeredOffset = playlistCenteredScrollOffset(
        viewportStart = layoutInfo.viewportStartOffset,
        viewportEnd = layoutInfo.viewportEndOffset,
        itemSize = itemSize,
    )
    animateScrollToItem(itemIndex, -centeredOffset)
}

internal fun playlistCenteredScrollOffset(
    viewportStart: Int,
    viewportEnd: Int,
    itemSize: Int,
): Int = ((viewportEnd - viewportStart - itemSize).coerceAtLeast(0) / 2) + viewportStart
