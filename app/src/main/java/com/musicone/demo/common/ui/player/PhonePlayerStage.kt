package com.musicone.demo

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt

@Composable
internal fun PhonePlayerStage(state: MusicOneUiState, track: MusicTrack, visualTrack: MusicTrack, motion: PageMotion,
    lyrics: Boolean, controlsBottomInset: Dp, controlsHeightPx: () -> Float,
    controlsHiddenProgress: () -> Float, viewModel: MusicOneViewModel,
    contentActivated: Boolean,
    onLyricsDismiss: () -> Unit, contentHorizontalPadding: Dp, modifier: Modifier,
    onLyricsMotion: (PhoneLyricsMotion) -> Unit = {}) {
    val favorites = LocalMusicFavorites.current
    val actionTrack = playerVisualActionTrack(track, visualTrack)
    val ink = LocalContentColor.current
    val spec = LocalPlayerLayoutSpec.current
    val playerVisible = LocalPlayerVisible.current
    val prewarming = LocalPlayerPrewarming.current
    val navigationExtension = playerButtonNavigationBottomExtension()
    val lyricExitAlignment = remember(lyrics) {
        if (lyrics) null else CompletableDeferred<Unit>()
    }
    val lyricsMotion = rememberPhoneLyricsMotion(lyrics, lyricExitAlignment)
    SideEffect { onLyricsMotion(lyricsMotion) }
    val touchRegion = remember { PhoneLyricsTouchRegion() }
    val textMarquee = playerTextMarqueeEnabled(motion.phase, motion.targetContentHandoff)
    val playingScale = rememberPlayerCoverScale(state.isPlaying, motion)
    val artworkCorner = phoneArtworkCorner(lyricsMotion.headerProgress)
    val artworkShape = remember(artworkCorner) { RoundedCornerShape(artworkCorner.dp) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(motion) {
        onDispose { listOf("cover", "title", "subtitle").forEach { motion.targets.remove(it) } }
    }
    fun shared(key: String, text: Boolean = false) = if (text) {
        Modifier.playerTextHandoffCapture(motion, key)
    } else Modifier.graphicsLayer {
        alpha = if (motion.moving && motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null) 0f else 1f
    }
    // 同一份封面、文字、爱心和歌词始终保留，只在放置阶段改变位置与图层缩放。
    Layout(modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }
        .phoneLyricsGesture(lyricsMotion, touchRegion, contentActivated && motion.phase == MotionPhase.SHOWN,
            lyrics, { target -> if (target != lyrics) onLyricsDismiss() }), content = {
        PlayerArtwork(visualTrack,
            shared("cover").clickable(enabled = lyrics, onClickLabel = "收起歌词", onClick = onLyricsDismiss),
            110.sp, artworkShape)
        TrackTitle(visualTrack, modifier = shared("title", text = true).playerRelatedInformation(actionTrack, !lyrics && lyricsMotion.lyricsProgress == 0f),
            style = MusicOneTextStyles.playerTitle.copy(fontSize = spec.titleSize),
            color = ink, marquee = true, marqueeRunning = textMarquee,
            badgeOnDark = ink.luminance() > .5f, showBadges = false, primaryTitleOnly = true)
        Text(musicOneUiAnnotatedString(playerPrimaryArtists(visualTrack.artists)), style = MusicOneTextStyles.playerArtist.copy(fontSize = spec.artistSize),
            color = ink.copy(alpha = .62f), maxLines = 1,
            modifier = shared("subtitle", text = true).playerRelatedInformation(actionTrack, !lyrics && lyricsMotion.lyricsProgress == 0f)
                .basicMarquee(iterations = if (textMarquee) Int.MAX_VALUE else 0))
        AnimatedFavoriteButton(visualTrack.id in favorites.state.ids, { favorites.toggle(actionTrack) },
            Modifier.playerDetailReveal(motion))
        if (contentActivated) {
            PlayerSyncedLyrics(
                track,
                state.lyricLoadState,
                viewModel,
                Modifier.playerLyricsDrawExtension(navigationExtension).playerControlsBackdrop(
                    controlsHeight = controlsHeightPx,
                    hiddenProgress = controlsHiddenProgress,
                    revealProgress = { lyricsMotion.lyricsProgress },
                    bottomExtension = navigationExtension,
                ).padding(horizontal = contentHorizontalPadding)
                    .then(if (lyrics) Modifier else Modifier.clearAndSetSemantics { }),
                active = playerVisible && (lyrics || lyricsMotion.lyricsProgress > .0005f),
                followPlayback = playerVisible && lyrics && !lyricsMotion.dragging && lyricsMotion.lyricsProgress == 1f && lyricsMotion.headerProgress == 1f,
                exitAlignment = lyricExitAlignment,
                openingAlignment = lyricsMotion.openingAlignment,
                prepareWhileHidden = prewarming || (playerVisible && !lyrics && lyricsMotion.lyricsProgress == 0f && !lyricsMotion.dragging),
                exitProgress = { 1f - lyricsMotion.lyricsProgress },
                trackTransitionDirection = state.trackTransitionDirection,
            )
        } else {
            Box(Modifier.fillMaxSize())
        }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val contentInset = contentHorizontalPadding.roundToPx().coerceAtLeast(0)
        val contentWidth = (width - contentInset * 2).coerceAtLeast(1)
        val coverContentHeight = (height - controlsBottomInset.roundToPx()).coerceAtLeast(1)
        val header = phoneLyricsHeaderHeight(spec.lyricCoverSize.value).dp.roundToPx()
        lyricsMotion.dragTravelPx = (height - header).toFloat().coerceAtLeast(1f)
        val infoHeight = 70.dp.roundToPx()
        val targetWidth = (contentWidth * spec.coverWidthFraction).roundToInt()
        val coverSide = minOf(targetWidth, (coverContentHeight - infoHeight - 24.dp.roundToPx()).coerceAtLeast(1), spec.coverMax.roundToPx())
        val compactSide = spec.lyricCoverSize.toPx()
        val textX = contentInset + spec.lyricCoverSize.toPx() + 14.dp.toPx()
        val heartSize = 48.dp.roundToPx()
        val textEndGap = 8.dp.roundToPx()
        // 标题始终按歌词页眉宽度测量，切换首帧不会因重新换行产生跳动。
        val textWidth = (width - contentInset - textX.roundToInt() - heartSize - textEndGap).coerceAtLeast(1)
        val cover = children[0].measure(Constraints.fixed(coverSide, coverSide))
        val title = children[1].measure(Constraints(maxWidth = textWidth))
        val artist = children[2].measure(Constraints(maxWidth = textWidth))
        lyricsMotion.contactProgress = lyricsContactProgress(
            height.toFloat(), header.toFloat(),
            coverContentHeight - infoHeight + 8.dp.toPx() + title.height + artist.height,
        )
        val heart = children[3].measure(Constraints.fixed(heartSize, heartSize))
        val lines = children[4].measure(Constraints.fixed(width, (height - header).coerceAtLeast(1)))
        layout(width, height) {
            val p = lyricsMotion.headerProgress
            val side = motionLerp(coverSide * playingScale.value, compactSide, p)
            val coverX = motionLerp(contentInset + (contentWidth - side) / 2f, contentInset.toFloat(), p)
            val coverY = motionLerp((coverContentHeight - infoHeight - side) / 2f, 10.dp.toPx(), p)
            touchRegion.cover = Rect(coverX, coverY, coverX + side, coverY + side)
            val titleX = motionLerp(contentInset.toFloat(), textX, p)
            val titleY = motionLerp((coverContentHeight - infoHeight + 8.dp.roundToPx()).toFloat(), 15.dp.toPx(), p)
            val textScale = motionLerp(1f, .9f, p)
            val artistY = titleY + title.height * textScale
            fun anchor(key: String, x: Float, y: Float, w: Float, h: Float, corner: Float = 0f, mark: Float = 0f,
                contentScale: Float = 1f, textSizeSp: Float = 0f) {
                motion.targets[key] = MotionAnchor(
                    Rect(origin + Offset(x, y), Size(w, h)), corner, mark, 1f, -1f,
                    contentScale = contentScale,
                    textSizeSp = textSizeSp,
                )
            }
            anchor("cover", coverX, coverY, side, side, 12f, 110f * side / coverSide, side / coverSide)
            anchor("title", titleX, titleY, title.width * textScale, title.height * textScale,
                contentScale = textScale, textSizeSp = spec.titleSize.value)
            anchor("subtitle", titleX, artistY, artist.width * textScale, artist.height * textScale,
                contentScale = textScale, textSizeSp = spec.artistSize.value)
            cover.placeWithLayer(coverX.roundToInt(), coverY.roundToInt()) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = side / coverSide; scaleY = side / coverSide
            }
            title.placeWithLayer(titleX.roundToInt(), titleY.roundToInt()) {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
            }
            artist.placeWithLayer(titleX.roundToInt(), artistY.roundToInt()) {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = textScale; scaleY = textScale
            }
            heart.placeWithLayer(width - contentInset - heartSize, motionLerp((coverContentHeight - infoHeight + 8.dp.roundToPx()).toFloat(), 17.dp.toPx(), p).roundToInt())
            lines.placeWithLayer(0, if (prewarming) header
                else motionLerp(height.toFloat(), header.toFloat(), lyricsMotion.lyricsProgress).roundToInt())
        }
    }
}

internal fun phoneLyricsHeaderHeight(compactCoverDp: Float): Float = maxOf(86f, compactCoverDp + 20f)
