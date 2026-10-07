package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp

@Composable
internal fun EntityPageHost(navigation: EntityNavigation, state: MusicOneUiState, player: MusicOneViewModel,
    bottomInset: Dp, createdPlaylists: List<MusicPlaylist>, onChanged: (MusicPlaylist) -> Unit) {
    navigation.pages.forEachIndexed { index, frame -> key(frame.key) {
        val top = index == navigation.pages.lastIndex
        val pull = remember(frame.key) { PlaylistPullState() }
        val pageLayer = rememberGraphicsLayer()
        frame.artistAvatar?.let { PrepareArtistAvatar(it) }
        SideEffect { frame.pageLayer = pageLayer }
        LaunchedEffect(frame.motion.phase) { if (frame.motion.phase == MotionPhase.SHOWN) pull.active = false }
        CompositionLocalProvider(LocalEntityFrame provides frame, LocalEntityActive provides top,
            LocalPlaylistPull provides pull,
            LocalPlaylistMotion provides frame.motion, LocalPlaylistCardTransition provides null,
            LocalPlaylistContentEntrance provides { 1f }) {
            // 下层保留组合和列表位置，不设置透明的全屏触摸覆盖层。
            Box(Modifier.fillMaxSize().onGloballyPositioned { frame.motion.updateHost(it.boundsInRoot()) }.graphicsLayer {
                alpha = if (frame.motion.phase == MotionPhase.HIDDEN || frame.motion.phase == MotionPhase.PREPARING) 0f
                    else 1f
            }.drawWithContent {
                // 子页只采样来源页；进入自身时不录制，以免把玻璃采样接成循环。
                if (frame.motion.phase == MotionPhase.SHOWN) {
                    pageLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(pageLayer)
                } else drawContent()
            }) {
                val returningMenu = frame.sourceMenu?.takeIf { it.liveBackdrop && !frame.motion.wantsOpen }?.surfaceLayer
                ExpandingPageSurface(frame.origin, { frame.motion.value },
                    if (returningMenu == null) frame.menuImage else null, translateContent = false,
                    sourceContent = returningMenu?.let { layer -> { EntityReturnMenuSurface(layer) } },
                    backgroundContent = { EntityPageGlass(frame) }) {
                    when (val target = frame.target) {
                        is EntityTarget.Artist -> ArtistScreen(target.singer, state, bottomInset, createdPlaylists,
                            { if (navigation.pages.lastOrNull() === frame) navigation.back() }, { tracks, selected ->
                                if (selected != null) player.playTrackNext(selected) else playEntityTracks(player, tracks)
                            }, onChanged)
                        is EntityTarget.Album -> EntityAlbumScreen(target.playlist, state, bottomInset, createdPlaylists,
                            { if (navigation.pages.lastOrNull() === frame) navigation.back() }, { tracks, selected ->
                                if (selected != null) player.playTrackNext(selected) else playEntityTracks(player, tracks)
                            }, onChanged, onShuffle = { player.playPlaylist(it, shuffle = true) })
                    }
                }
                if (frame.motion.moving) frame.sourceMenu?.artistArtworkState?.let { artwork ->
                    RetainedMenuArtistArtworkOverlay(artwork, frame.motion.hostBounds, frame.motion.value,
                        frame.sourceKey.takeIf { frame.motion.hasSharedCover })
                }
                when (val target = frame.target) {
                    is EntityTarget.Artist -> FlyingPlaylistArtwork(frame.motion, target.singer.artwork,
                        0xFFCEDCD7, 0xFF779187, target.singer.name.take(1), followPull = false, centeredMark = true,
                        artistAvatar = frame.artistAvatar)
                    is EntityTarget.Album -> {
                        FlyingPlaylistArtwork(frame.motion, target.playlist.artworkUrl,
                            target.playlist.artworkStart, target.playlist.artworkEnd, target.playlist.artworkMark,
                            followPull = false)
                    }
                }
            }
        }
    } }
}

private fun playEntityTracks(player: MusicOneViewModel, tracks: List<MusicTrack>) {
    if (tracks.isEmpty()) return
    val first = tracks.first()
    player.playPlaylist(MusicPlaylist("entity-playback", MusicSource.QQ, first.artists, "", "", tracks.size,
        first.artworkStart, first.artworkEnd, first.artworkMark, tracks))
}
