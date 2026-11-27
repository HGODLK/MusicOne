package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选的横向货架组件：滑动区域延伸到页面边缘，标题和首尾项保留指定边距。 */
interface SourceHomeShelfComponents {
    @Composable fun SongShelf(id: String, title: String, pages: List<List<SourceTrackDetails>>,
        player: SourcePlayerState = SourcePlayerState(), showFavorites: Boolean = true,
        edgeInsetDp: Float = 0f, modifier: Modifier = Modifier, onClick: (SourceTrackDetails) -> Unit)

    @Composable fun CollectionShelf(collections: List<SourceCollection>, edgeInsetDp: Float = 0f,
        onPlay: (SourceCollection) -> Unit, onOpen: (SourceCollection) -> Unit)
}
