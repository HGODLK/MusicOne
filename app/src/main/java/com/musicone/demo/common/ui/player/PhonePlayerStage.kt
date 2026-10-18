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
    controlsHiddenProgress: () -> Float, viewModel: MusicPlaybackRuntime,
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
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topWindowInset = WindowInsets.statusBars.getTop(density)
    val lyricHeader = phoneLyricsHeaderHeight(spec.lyricCoverSize.value).dp
    val lyricTopExtension = with(density) { (lyricHeader + PHONE_PLAYER_TOP_PADDING).roundToPx() } + topWindowInset
    val topGlass = with(density) { phoneLyricsTopGlass(lyricHeader.toPx(),
        lyricTopExtension.toFloat(), 10.dp.toPx() + spec.lyricCoverSize.toPx(),
        PHONE_LYRICS_TOP_GLASS_LIMIT.toPx(), 8.dp.toPx()) }
    val readingBounds = LyricReadingBounds(topGlass.clearStart,
        navigationExtension + controlsHeightPx() + with(density) { PLAYER_CONTROLS_WIDENED_BLUR_HEIGHT.toPx() })
    val residentLyrics = LocalPlayerResourcePreparationAllowed.current
    val lyricExitAlignment = remember(lyrics, residentLyrics) {
        if (lyrics || residentLyrics) null else CompletableDeferred<Unit>()
    }
    val lyricsMotion = rememberPhoneLyricsMotion(lyrics, lyricExitAlignment, playerVisible,
        prepareOpening = !residentLyrics)
    val drawingAtRest = remember(lyricsMotion, playerVisible, lyrics, prewarming) {
        derivedStateOf { prewarming || phoneLyricsStandby(playerVisible, lyrics, lyricsMotion.lyricsProgress,
            lyricsMotion.headerProgress, lyricsMotion.dragging) }
    }
    val drawAtRest by drawingAtRest
    val residency = rememberLyricResidency(residentLyrics,
        playerVisible && motion.phase == MotionPhase.SHOWN && motion.wantsOpen && state.playerExpanded,
        drawAtRest || !playerVisible)
    val latestDrawingAtRest by rememberUpdatedState(drawingAtRest)
    // 图层放置、玻璃遮罩和文字边界在绘制时共读待命状态，避免各自沿用旧坐标。
    val boundaryWindow = remember(lyricsMotion, lyricTopExtension) {
        LyricBoundaryWindow(lyricTopExtension.toFloat(),
            offset = { if (latestDrawingAtRest.value) 0f else lyricsMotion.windowOffsetPx },
            velocity = { if (latestDrawingAtRest.value) 0f else lyricsMotion.windowVelocityPxPerSecond })
    }
    val recordWindowLayout = remember(lyricsMotion, density, spec.mode) {
        { layout: androidx.compose.foundation.lazy.LazyListLayoutInfo, protection: LyricBlurProtection ->
            lyricsMotion.spatialTravel.record(layout, density, protection,
                lyricTypographyForWidth(spec.mode, layout.viewportSize.width / density.density)) }
    }
    SideEffect { onLyricsMotion(lyricsMotion) }
    val touchRegion = remember { PhoneLyricsTouchRegion() }
    val playerTouchRegions = LocalPlayerTouchRegions.current
    DisposableEffect(playerTouchRegions) {
        onDispose { playerTouchRegions?.lyricsGestureBounds = Rect.Zero }
    }
    val textMarquee = playerTextMarqueeEnabled(motion.phase, motion.targetContentHandoff)
    val playingScale = rememberPlayerCoverScale(state.isPlaying, motion)
    val artworkCorner = phoneArtworkCorner(lyricsMotion.headerProgress)
    val artworkShape = remember(artworkCorner) { RoundedCornerShape(artworkCorner.dp) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(motion) {
        onDispose { listOf("cover", "title", "subtitle").forEach { motion.targets.remove(it) } }
    }
    fun shared(key: String, text: Boolean = false) = if (text) {
        Modifier.playerTextHandoffCapture(motion, key, visualTrack)
    } else Modifier.graphicsLayer {
        alpha = if (motion.moving && motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null) 0f else 1f
    }
    val informationMenuEnabled = playerInformationMenuEnabled(actionTrack.source, lyrics,
        lyricsMotion.lyricsProgress, lyricsMotion.headerProgress, lyricsMotion.dragging)
    // 同一份封面、文字、爱心和歌词始终保留，只在放置阶段改变位置与图层缩放。
    Layout(modifier = modifier.onGloballyPositioned {
        origin = it.positionInRoot()
        playerTouchRegions?.lyricsGestureBounds = if (lyrics || lyricsMotion.lyricsProgress > .0005f)
            touchRegion.closing.translate(origin) else Rect.Zero
    }
        .phoneLyricsGesture(lyricsMotion, touchRegion, contentActivated && motion.phase == MotionPhase.SHOWN,
            lyrics, { target -> if (target != lyrics) onLyricsDismiss() }), content = {
        PlayerArtwork(visualTrack, motion,
            shared("cover").clickable(enabled = lyrics, onClickLabel = "收起歌词", onClick = onLyricsDismiss),
            110.sp, artworkShape)
        TrackTitle(visualTrack, modifier = shared("title", text = true).playerRelatedInformation(actionTrack, informationMenuEnabled)
            .onlineLyricOffset(actionTrack, lyrics && lyricsMotion.lyricsProgress == 1f &&
                lyricsMotion.headerProgress == 1f && !lyricsMotion.dragging),
            style = MusicOneTextStyles.playerTitle.copy(fontSize = spec.titleSize),
            color = ink, marquee = true, marqueeRunning = textMarquee,
            badgeOnDark = ink.luminance() > .5f, showBadges = false, primaryTitleOnly = true)
        Text(musicOneUiAnnotatedString(playerPrimaryArtists(visualTrack.artists)), style = MusicOneTextStyles.playerArtist.copy(fontSize = spec.artistSize),
            color = ink.copy(alpha = .62f), maxLines = 1,
            modifier = shared("subtitle", text = true).playerRelatedInformation(actionTrack, informationMenuEnabled)
                .basicMarquee(iterations = if (textMarquee) Int.MAX_VALUE else 0))
        AnimatedFavoriteButton(visualTrack.id in favorites.state.ids, { favorites.toggle(actionTrack) },
            Modifier.playerDetailReveal(motion))
        if (contentActivated) {
            PlayerSyncedLyrics(
                track,
                state.lyricLoadState,
                viewModel,
                Modifier.playerLyricsDrawExtension(bottom = navigationExtension, top = lyricTopExtension).playerControlsBackdrop(
                    controlsHeight = controlsHeightPx,
                    hiddenProgress = controlsHiddenProgress,
                    revealProgress = { lyricsMotion.lyricsProgress },
                    bottomExtension = navigationExtension,
                    topExtension = lyricTopExtension,
                    topBuffer = lyricTopExtension,
                    windowMotionOffset = boundaryWindow.offset,
                    focusProtection = { lyricsMotion.spatialTravel.blurProtection },
                    topGlass = topGlass,
                ).lyricResidentRefresh(residency).padding(horizontal = contentHorizontalPadding)
                    .then(if (lyrics) Modifier else Modifier.clearAndSetSemantics { }),
                active = playerVisible && (lyrics || lyricsMotion.lyricsProgress > .0005f),
                followPlayback = playerVisible && lyrics && !lyricsMotion.dragging,
                animateTrackChange = playerVisible && !lyricsMotion.dragging,
                // 手势展开时尚未提交 lyrics，不能被关闭态的旧对齐请求挡住。
                exitAlignment = if (lyricsMotion.dragging && !lyrics) null else lyricExitAlignment,
                openingAlignment = lyricsMotion.openingAlignment,
                prepareWhileHidden = drawAtRest && !residentLyrics,
                revealTopLineBeforeEntrance = !residentLyrics,
                limitTransitionHistory = !residentLyrics,
                residency = residency,
                topExtensionPx = lyricTopExtension,
                topBufferPx = lyricTopExtension,
                readingBounds = readingBounds,
                boundaryWindow = boundaryWindow,
                exitProgress = { 1f - lyricsMotion.lyricsProgress },
                entranceProgress = { lyricsMotion.entranceProgress },
                intermediateSeek = !((lyricsMotion.lyricsProgress == 0f && lyricsMotion.headerProgress == 0f) ||
                    (lyricsMotion.lyricsProgress == 1f && lyricsMotion.headerProgress == 1f)),
                lyricsGestureHeld = lyricsMotion.dragging,
                closingLyricsDrag = lyricsMotion.draggingTowardClose,
                heldTrackChange = lyricsMotion.dragging &&
                    lyricsMotion.entranceProgress > 0f && lyricsMotion.entranceProgress < 1f,
                trackTransitionDirection = state.trackTransitionDirection,
                onWindowLayout = recordWindowLayout,
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
        lyricsMotion.dragTravelPx = phoneLyricsWindowTravel(height, header, lyricTopExtension, lyricTopExtension)
        // 使用原有开合行程；底部玻璃绘制仍保持现有参数。
        lyricsMotion.controlsSpacePx = controlsBottomInset.toPx().coerceAtLeast(0f) + PLAYER_CONTROLS_BLUR_FADE_HEIGHT.toPx()
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
        val heart = children[3].measure(Constraints.fixed(heartSize, heartSize))
        val lines = children[4].measure(Constraints.fixed(width,
            (height - header + lyricTopExtension).coerceAtLeast(1)))
        layout(width, height) {
            val p = lyricsMotion.headerProgress
            val side = motionLerp(coverSide * playingScale.value, compactSide, p)
            val coverX = motionLerp(contentInset + (contentWidth - side) / 2f, contentInset.toFloat(), p)
            val coverY = motionLerp((coverContentHeight - infoHeight - side) / 2f, 10.dp.toPx(), p)
            val titleX = motionLerp(contentInset.toFloat(), textX, p)
            val titleY = motionLerp((coverContentHeight - infoHeight + 8.dp.roundToPx()).toFloat(), 15.dp.toPx(), p)
            val textScale = motionLerp(1f, .9f, p)
            val artistY = titleY + title.height * textScale
            // 封面页的留白也可上滑；整个歌词页眉可下划收起，正文继续交给列表滚动。
            touchRegion.opening = Rect(0f, 0f, width.toFloat(), coverContentHeight.toFloat())
            touchRegion.closing = Rect(0f, 0f, width.toFloat(), header.toFloat())
            playerTouchRegions?.lyricsGestureBounds = if (lyrics || lyricsMotion.lyricsProgress > .0005f)
                touchRegion.closing.translate(origin) else Rect.Zero
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
            // 先绘制扩展到屏幕顶端的歌词，再盖上原位的封面、标题和收藏按钮。
            // 固定阅读槽位，位移与歌词遮罩都在图层阶段读取当前进度，首帧从屏外接入。
            lines.placeWithLayer(0, header - lyricTopExtension) {
                // 完全关闭时在原阅读槽录制真实歌词；既有玻璃层只记录来源，不输出像素。
                translationY = boundaryWindow.offset()
            }
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
        }
    }
}

internal fun phoneLyricsHeaderHeight(compactCoverDp: Float): Float = maxOf(86f, compactCoverDp + 20f)
