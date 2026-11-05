package com.musicone.sourceintegration

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.*
import com.musicone.source.sdk.*

/** 基础控件直接沿用公共展示策略，避免包另造封面或权益标识。 */
internal class SourcePublicBasicComponents(private val access: SourcePublicComponentAccess) : SourceBasicComponents {
    private class BackdropHandle(val access: SourcePublicComponentAccess, val layer: GraphicsLayer) : SourceBackdropState {
        override val sourceId get(): String { access.check(); return access.sourceId }
        var bounds by mutableStateOf(Rect.Zero)
    }
    @Composable override fun rememberBackdropState(key: String): SourceBackdropState {
        access.check()
        val layer = rememberGraphicsLayer()
        return remember(access, key, layer) { BackdropHandle(access, layer) }
    }
    private fun handle(state: SourceBackdropState): BackdropHandle {
        access.check()
        require(state.sourceId == access.sourceId) { "玻璃采样不属于当前音源" }
        return requireNotNull(state as? BackdropHandle) { "玻璃采样必须由公共控件创建" }
    }
    @Composable override fun Backdrop(state: SourceBackdropState, modifier: Modifier, content: @Composable () -> Unit) {
        val value = handle(state)
        // 只采样调用方指定的背景，不包含上层按钮，避免玻璃递归采样自身。
        Box(modifier.onGloballyPositioned { value.bounds = it.boundsInRoot() }.drawWithContent {
            value.layer.record { this@drawWithContent.drawContent() }
            drawLayer(value.layer)
        }) { content() }
    }
    @Composable private fun HeaderButton(settings: Boolean, backdrop: SourceBackdropState?, modifier: Modifier, onClick: () -> Unit) {
        val value = backdrop?.let(::handle)
        val page = if (settings) MusicOnePage.MY else MusicOnePage.HOME
        access.Host { Box(modifier) {
            PrimaryPageActionButton(page, { page.ordinal.toFloat() }, value?.layer, value?.bounds ?: Rect.Zero,
                access.event(onClick), access.event(onClick))
        } }
    }
    @Composable override fun SearchButton(backdrop: SourceBackdropState?, modifier: Modifier, onClick: () -> Unit) {
        HeaderButton(false, backdrop, modifier, onClick)
    }
    @Composable override fun SettingsButton(backdrop: SourceBackdropState?, modifier: Modifier, onClick: () -> Unit) {
        HeaderButton(true, backdrop, modifier, onClick)
    }
    @Composable override fun Artwork(artwork: SourceArtwork, modifier: Modifier, cornerDp: Float, circular: Boolean, markSizeSp: Float) {
        access.Host { RemoteArtwork(artwork.url, artwork.startArgb, artwork.endArgb, artwork.mark, modifier,
            markSizeSp.sp, if (circular) CircleShape else RoundedCornerShape(cornerDp.dp)) }
    }
    @Composable override fun SongTitle(track: SourceTrackDetails, modifier: Modifier, marquee: Boolean, showBadges: Boolean, onDark: Boolean) {
        access.check(track)
        access.Host { TrackTitle(track.toMusicTrack(), modifier, marquee = marquee, showBadges = showBadges, badgeOnDark = onDark) }
    }
    @Composable override fun TrackBadges(track: SourceTrackDetails, onDark: Boolean) {
        access.check(track)
        access.Host { Row {
            val song = track.toMusicTrack()
            OfflineTrackBadge(song, onDark)
            if (LocalTrackAccessBadgesVisible.current) mainSourceDisplayedAccessBadge(song)?.let { SongAccessBadge(it, onDark) }
        } }
    }
    @Composable override fun PlaybackActions(collection: SourceCollection, playable: Boolean, selectedSortId: String?,
        onPlay: () -> Unit, onShuffle: () -> Unit, onSort: (String) -> Unit) {
        access.check(collection)
        val sort = PlaylistSort.entries.firstOrNull { it.name == selectedSortId } ?: PlaylistSort.ADDED_DESC
        access.Host { PlaylistPlaybackActions(sort, playable, access.event(onPlay), access.event(onShuffle),
            access.valueEvent { value: PlaylistSort -> onSort(value.name) }, MusicSource.fromId(access.sourceId)) }
    }
    @Composable override fun ActionButton(title: String, enabled: Boolean, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
        access.Host { LibraryActionButton(title, access.event(onClick), modifier, enabled = enabled, primary = primary) }
    }
    @Composable override fun ActionRow(title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
        access.Host { LibraryActionRow(title, subtitle, enabled, onClick = access.event(onClick)) }
    }
    @Composable override fun FormField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
        access.Host { LibraryFormField(label, value, access.valueEvent(onChange), enabled) }
    }
    @Composable override fun SettingsGroup(title: String?, modifier: Modifier, content: @Composable () -> Unit) {
        val packageContext = androidx.compose.ui.platform.LocalContext.current
        access.Host { LibrarySettingsGroup(title, modifier) { SourceHostUiResources(packageContext, content) } }
    }
}
