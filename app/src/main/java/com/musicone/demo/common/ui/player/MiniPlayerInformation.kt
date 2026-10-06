package com.musicone.demo

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RowScope.MiniPlayerInformation(track: MusicTrack, favorite: Boolean, swipe: MiniPlayerSwipe,
    onOpen: () -> Unit, onFavorite: () -> Unit) {
    val motion = LocalPlayerMotion.current
    val favorites = LocalMusicFavorites.current.state.ids
    // 展开页完全覆盖迷你播放器时，不为被遮住的封面和文字运行切歌淡变。
    val duration = if (swipe.active || motion?.phase == MotionPhase.SHOWN) 0 else 260
    // 转场中的封面与飞行层、大封面同速，避免收起落地时跳到另一混合比例。
    val artworkDuration = if (motion?.moving == true) 420 else duration
    val artwork by rememberPlayerArtwork(track)
    val outgoingArtwork by rememberPlayerArtwork(swipe.outgoing ?: track)
    val incomingArtwork by rememberPlayerArtwork(swipe.incoming ?: track)
    Box(Modifier.size(48.dp).clickable(enabled = !swipe.active, onClick = onOpen)
        .semantics { contentDescription = "打开播放页" }
        .motionAnchor(motion, "cover", false, corner = 16f, markSize = 21f, markX = 1f, markY = -1f)) {
        ReadyArtworkCrossfade(
            artwork,
            artworkDuration,
            Modifier.fillMaxSize(),
            displayKey = track.source to track.id,
            onDisplayed = { swipe.onTargetBaseArtworkDrawn(track, it) },
        ) { MiniArtworkFrame(it) }
        if (swipe.active) {
            val target = swipe.incoming!!
            MiniArtworkFrame(outgoingArtwork)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = swipe.amount }) {
                ReadyArtworkCrossfade(
                    incomingArtwork,
                    MINI_SWIPE_ARTWORK_FADE_MILLIS,
                    Modifier.fillMaxSize(),
                    displayKey = Triple(target.source, target.id, swipe.settling),
                    onDisplayed = { swipe.onTargetOverlayArtworkDrawn(target, it) },
                ) { MiniArtworkFrame(it) }
            }
        }
    }
    Column(Modifier.weight(1f).clickable(enabled = !swipe.active, onClick = onOpen).padding(horizontal = 11.dp)) {
        Box(Modifier.fillMaxWidth().motionAnchor(motion, "title", false, textSizeSp = 14f)) {
            MiniTextTransition(track, swipe, duration) { value ->
                Text(musicOneUiAnnotatedString(value.title), Modifier.miniPlayerInkRegion("title"),
                    style = MusicOneTextStyles.miniPlayerTitle, color = miniPlayerInkColor("title"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).motionAnchor(motion, "subtitle", false, textSizeSp = 12f)) {
            MiniTextTransition(track, swipe, duration) { value ->
                Text(musicOneUiAnnotatedString(value.artists), Modifier.miniPlayerInkRegion("artist"),
                    style = MusicOneTextStyles.miniPlayerArtist, color = miniPlayerInkColor("artist"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    IconButton(onFavorite, enabled = !swipe.active, modifier = Modifier
        .motionAnchor(motion, "miniFavorite", false, hideDuringMotion = false)
        .graphicsLayer { alpha = miniPlayerAccessoryAlpha(motion?.value ?: 0f) }) {
        Box(Modifier.graphicsLayer { alpha = if (swipe.active) 0f else 1f }) {
            Crossfade(favorite, animationSpec = musicMotion(duration), label = "迷你收藏状态") { liked -> MiniFavoriteIcon(liked) }
        }
        if (swipe.active) {
            Box(Modifier.graphicsLayer { alpha = 1f - swipe.amount }) { MiniFavoriteIcon(swipe.outgoing!!.id in favorites) }
            Box(Modifier.graphicsLayer { alpha = swipe.amount }) { MiniFavoriteIcon(swipe.incoming!!.id in favorites) }
        }
    }
}

private const val MINI_SWIPE_ARTWORK_FADE_MILLIS = 280

@Composable
private fun MiniArtworkFrame(frame: PlayerArtworkFrame) {
    ArtworkBitmapOrPlaceholder(frame.bitmap, frame.identity.start, frame.identity.end, frame.identity.mark,
        Modifier.fillMaxSize(), 21.sp, RoundedCornerShape(16.dp), androidx.compose.ui.Alignment.TopEnd)
}

@Composable
private fun MiniFavoriteIcon(favorite: Boolean) {
    Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏",
        tint = if (favorite) PlayerFavoriteColor else miniPlayerInkColor("favorite"),
        modifier = Modifier.size(18.dp).miniPlayerInkRegion("favorite"))
}

@Composable
private fun MiniTextTransition(track: MusicTrack, swipe: MiniPlayerSwipe, duration: Int,
    content: @Composable (PlayerTextPresentation) -> Unit) {
    val presented = track.playerTextPresentation()
    Box {
        Box(Modifier.graphicsLayer { alpha = if (swipe.active) 0f else 1f }) {
            Crossfade(presented,
                animationSpec = musicMotion(duration), label = "迷你歌曲信息") { content(it) }
        }
        if (swipe.active) {
            val direction = if (swipe.next) -1f else 1f
            Box(Modifier.graphicsLayer {
                alpha = 1f - swipe.amount
                translationX = direction * 28.dp.toPx() * swipe.amount
            }) { content(swipe.outgoing!!.playerTextPresentation()) }
            Box(Modifier.graphicsLayer {
                alpha = swipe.amount
                translationX = -direction * 28.dp.toPx() * (1f - swipe.amount)
            }) { content(swipe.incoming!!.playerTextPresentation()) }
        }
    }
}
