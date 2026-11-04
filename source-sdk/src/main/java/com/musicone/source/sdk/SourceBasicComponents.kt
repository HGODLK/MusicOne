package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选的页面玻璃采样句柄，内部图层不进入音源接口。 */
interface SourceBackdropState { val sourceId: String }

/** 页面组合用的基础展示控件，保持宿主已有的封面与歌曲角标策略。 */
interface SourceBasicComponents {
    @Composable fun rememberBackdropState(key: String): SourceBackdropState
    @Composable fun Backdrop(state: SourceBackdropState, modifier: Modifier = Modifier, content: @Composable () -> Unit)
    @Composable fun SearchButton(backdrop: SourceBackdropState? = null, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun SettingsButton(backdrop: SourceBackdropState? = null, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun Artwork(artwork: SourceArtwork, modifier: Modifier = Modifier, cornerDp: Float = 16f,
        circular: Boolean = false, markSizeSp: Float = 24f)
    @Composable fun SongTitle(track: SourceTrackDetails, modifier: Modifier = Modifier,
        marquee: Boolean = false, showBadges: Boolean = true, onDark: Boolean = false)
    @Composable fun TrackBadges(track: SourceTrackDetails, onDark: Boolean = false)
    @Composable fun PlaybackActions(collection: SourceCollection, playable: Boolean,
        selectedSortId: String? = null, onPlay: () -> Unit, onShuffle: () -> Unit, onSort: (String) -> Unit = {})
    @Composable fun ActionButton(title: String, enabled: Boolean = true, primary: Boolean = true,
        modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun ActionRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit)
    @Composable fun FormField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit)
    @Composable fun SettingsGroup(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit)
}
