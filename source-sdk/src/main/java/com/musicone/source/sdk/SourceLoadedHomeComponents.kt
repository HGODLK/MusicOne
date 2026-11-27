package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选扩展：传入已加载好的歌曲，让日推卡片保留完整的过渡动画。 */
interface SourceLoadedHomeComponents {
    @Composable fun DailyCard(collection: SourceCollection, tracks: List<SourceTrackDetails>,
        date: String? = null, loading: Boolean = false, message: String? = null,
        modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun PersonalizedCards(collection: SourceCollection, tracks: List<SourceTrackDetails>,
        date: String? = null, loading: Boolean = false, message: String? = null,
        radioActive: Boolean = false, radioPlaying: Boolean = false, radioLoading: Boolean = false,
        radioVisible: Boolean = true, modifier: Modifier = Modifier,
        onDailyClick: () -> Unit, onRadioClick: () -> Unit)
}
