package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

/** 旋转宿主提供横向画布；这里仅负责布局，不改变 Activity 的方向。 */
@Composable
internal fun PhoneLandscapePlayer(state: MusicOneUiState, track: MusicTrack, visualTrack: MusicTrack, motion: PageMotion,
    viewModel: MusicPlaybackRuntime, contentActivated: Boolean, showLyrics: Boolean, lyricsEndInset: Dp,
    onCloseLyrics: () -> Unit = {}) {
    val favorites = LocalMusicFavorites.current
    val actionTrack = playerVisualActionTrack(track, visualTrack)
    val ink = LocalContentColor.current
    val visible = LocalPlayerVisible.current && contentActivated && state.playerExpanded
    val density = LocalDensity.current
    val lyricsColumn = LocalPhoneLyricsColumnWidth.current
    val physicalWindow = LocalView.current
    val playingScale = rememberPlayerCoverScale(state.isPlaying, motion)
    val retainedLyrics = LocalRetainedPhoneLyricsMotion.current
    val localPanel = remember { Animatable(if (showLyrics) 1f else 0f) }
    // 共用当前实际开合进度，半途转向也从正在显示的位置继续退场。
    val panel = retainedLyrics?.lyrics ?: localPanel
    val lyricsDisplayed by remember(panel, showLyrics, visible) { derivedStateOf {
        visible && (showLyrics || panel.value > 0f)
    } }
    var lyricsTravel by remember { mutableFloatStateOf(1f) }
    val rotation = LocalPhonePlayerRotation.current
    val presentation = LocalPhonePlayerPresentation.current
    val preparingTurn = presentation?.bodyMoving == true || presentation?.preparation?.preparing == true
    val fadingTurn = presentation?.preparation?.fading == true
    val closed by remember(panel, showLyrics) { derivedStateOf { !showLyrics && panel.value == 0f } }
    val standby = visible && motion.phase == MotionPhase.SHOWN && closed && !preparingTurn && !fadingTurn
    val residentLyrics = LocalPlayerResourcePreparationAllowed.current
    val lyricExitAlignment = remember(showLyrics, residentLyrics) {
        if (showLyrics || residentLyrics) null else kotlinx.coroutines.CompletableDeferred<Unit>()
    }
    val residency = rememberLyricResidency(residentLyrics, standby, closed,
        continuousAllowed = presentation?.turn?.isTurning != true)
    // 与竖屏共用逐行边界明暗，横屏没有页眉扩展和独立的列表窗口位移。
    val lyricBoundary = remember { LyricBoundaryWindow(topBuffer = 0f, offset = { 0f }, partialTop = true) }
    DisposableEffect(presentation) { onDispose { presentation?.additionalMotion("landscapeLyrics", false) } }
    LaunchedEffect(showLyrics, preparingTurn, fadingTurn) {
        if (preparingTurn) return@LaunchedEffect
        val velocity = panel.velocity
        val target = if (showLyrics) 1f else 0f
        if (retainedLyrics == null && panel.value != target)
            presentation?.turn?.followLayoutMotion(PhonePlayerPresentation.BodyKeys)
        presentation?.additionalMotion("landscapeLyrics", panel.value != target)
        try {
        if (retainedLyrics != null) retainedLyrics.moveLandscape(showLyrics, lyricsTravel, animate = !fadingTurn)
        else if (fadingTurn || ExperiencePreferences.options.reduceMotion) panel.snapTo(target)
        else panel.animateTo(target,
            musicSpring(stiffness = if (showLyrics) PHONE_LYRICS_OPEN_STIFFNESS else 210f,
                visibilityThreshold = .05f / lyricsTravel.coerceAtLeast(1f)),
            initialVelocity = velocity)
        } finally { presentation?.additionalMotion("landscapeLyrics", false) }
    }
    val ownedTargets = remember(motion) { mutableMapOf<String, MotionAnchor>() }
    DisposableEffect(motion) {
        onDispose { ownedTargets.forEach { (key, anchor) ->
            if (motion.targets[key] === anchor) motion.targets.remove(key)
        } }
    }
    fun shared(key: String, text: Boolean = false) = if (text) {
        Modifier.playerTextHandoffCapture(motion, key, visualTrack)
    } else Modifier.graphicsLayer {
        alpha = if (motion.moving && motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null) 0f else 1f
    }
    val anchorLayout = remember { PhoneLandscapeAnchorLayout() }
    val anchorOwner = remember { Any() }
    val anchorGeneration = presentation?.openingAnchors?.generation ?: 0
    fun publishAnchors() {
        val anchors = anchorLayout.physicalAnchors(rotation, motion.hostBounds) ?: return
        val accepted = presentation?.openingAnchors?.publish(anchorGeneration, rotation, anchors, motion)
            ?: run { motion.targets.putAll(anchors); true }
        if (accepted) anchors.keys.forEach { key -> motion.targets[key]?.let { ownedTargets[key] = it } }
    }
    SideEffect { presentation?.openingAnchors?.attach(anchorOwner, ::publishAnchors) }
    DisposableEffect(presentation, anchorOwner) {
        onDispose { presentation?.openingAnchors?.detach(anchorOwner) }
    }
    CompositionLocalProvider(LocalPlayerLayoutSpec provides CompactPlayerLayoutSpec) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val canvasWidthDp = maxWidth.value
            val touchPadding = maxWidth * .0783f
            val touchHeight = maxHeight
            val readingBounds = with(density) { LyricReadingBounds(
                (maxHeight * .0664f + 24.dp).toPx(),
                (touchHeight - maxHeight * .8947f + 24.dp).toPx()) }
            fun lyricsPivot(size: Size): Offset {
                presentation?.lyricsAnchor?.fraction(size,
                    IntSize(size.width.roundToInt(), size.height.roundToInt()), Offset.Zero)?.let { return it }
                val width = (size.width - with(density) { touchPadding.roundToPx() }).coerceAtLeast(1f)
                val row = lyricTypographyForWidth(CompactPlayerLayoutSpec.mode, width / density.density).estimatedRowHeightDp
                val focus = with(density) {
                    (lyricReadingAnchorDp(size.height / density.density, readingBounds, density, row) + row / 2f).dp.toPx()
                }
                return Offset(0f, (focus / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            CompositionLocalProvider(LocalLandscapeLyricsTouchPadding provides touchPadding) {
            Layout(modifier = Modifier.fillMaxSize().onGloballyPositioned {
                anchorLayout.coordinates = it
                publishAnchors()
            }, content = {
                PhoneTurnElement("cover", modifier = Modifier.graphicsLayer {
                    scaleX = playingScale.value; scaleY = scaleX
                }, cornerRadius = { with(density) { 12.dp.toPx() } }) {
                Box(Modifier.phoneLandscapeArtworkGesture(visible,
                    viewModel::togglePlay, viewModel::requestPrevious, viewModel::requestNext)) {
                    PhoneTurningArtwork(visualTrack, motion, Modifier.fillMaxSize().then(shared("cover")),
                        (canvasWidthDp * .1f).sp, 12f)
                }
                }
                PhoneTurnElement("title", uniformScale = true) {
                TrackTitle(visualTrack,
                    modifier = shared("title", text = true).landscapePlayerInformation(actionTrack, visible, showLyrics),
                    style = MusicOneTextStyles.playerTitle.copy(fontSize = CompactPlayerLayoutSpec.titleSize),
                    color = ink, marquee = true, marqueeRunning = visible,
                    badgeOnDark = ink.luminance() > .5f, showBadges = false, primaryTitleOnly = true)
                }
                PhoneTurnElement("artist", uniformScale = true) {
                Text(musicOneUiAnnotatedString(playerPrimaryArtists(visualTrack.artists)),
                    style = MusicOneTextStyles.playerArtist.copy(fontSize = CompactPlayerLayoutSpec.artistSize),
                    color = ink.copy(alpha = .62f), maxLines = 1,
                    modifier = shared("subtitle", text = true).landscapePlayerInformation(actionTrack, visible, showLyrics)
                        .basicMarquee(iterations = if (visible) Int.MAX_VALUE else 0))
                }
                PhoneTurnElement("favorite") {
                PlayerFavoriteButton(actionTrack, visualTrack.id in favorites.state.ids,
                    { favorites.toggle(actionTrack) }, Modifier.size(48.dp), enabled = visible)
                }
                if (contentActivated) PhoneLyricsSlot(PhoneLyricsSlot(PhoneLyricsViewport(landscape = true),
                    pivot = ::lyricsPivot, hidden = { !showLyrics && panel.value <= 0f },
                    offset = {
                        val distance = (1f - panel.value) * lyricsTravel
                        val angle = rotation * Math.PI / 180.0
                        Offset(-sin(angle).toFloat() * distance, cos(angle).toFloat() * distance)
                    }, velocity = {
                        val speed = -(retainedLyrics?.visibleLyricsVelocity ?: panel.velocity) * lyricsTravel
                        val angle = rotation * Math.PI / 180.0
                        Offset(-sin(angle).toFloat() * speed, cos(angle).toFloat() * speed)
                    })) {
                CompositionLocalProvider(
                    LocalPlayerLyricsMoving provides (motion.moving || motion.phase == MotionPhase.PREPARING || panel.isRunning),
                    LocalPlayerLyricsDisplayed provides lyricsDisplayed) {
                    PlayerSyncedLyrics(track, state.lyricLoadState, viewModel,
                        Modifier.fillMaxSize().then(if (lyricsDisplayed) Modifier else Modifier.clearAndSetSemantics { })
                            .landscapeLyricsTopPull(retainedLyrics?.listEdge, visible && showLyrics, onCloseLyrics),
                        active = visible && showLyrics,
                        followPlayback = visible && showLyrics, animateTrackChange = visible && showLyrics,
                        exitAlignment = lyricExitAlignment,
                        openingAlignment = retainedLyrics?.openingAlignment,
                        prepareWhileHidden = standby && !residentLyrics,
                        revealTopLineBeforeEntrance = !residentLyrics,
                        limitTransitionHistory = !residentLyrics,
                        residency = residency,
                        exitProgress = { 1f - panel.value },
                        entranceProgress = { panel.value },
                        readingBounds = readingBounds,
                        boundaryWindow = lyricBoundary,
                        trackTransitionDirection = state.trackTransitionDirection)
                }
                }
                else Box(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize())
                PlayerProgressTimeline(viewModel, null, Modifier.fillMaxWidth(), compactBottomTrack = true)
            }) { children, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                fun x(fraction: Float) = (w * fraction).roundToInt()
                fun y(fraction: Float) = (h * fraction).roundToInt()
                val buttonSize = with(density) { 48.dp.roundToPx() }
                val coverSide = minOf(x(.31f), (h * .72f).roundToInt()).coerceAtLeast(1)
                val cover = children[0].measure(Constraints.fixed(coverSide, coverSide))
                val informationLeft = x(.08f)
                // 红心的可见右缘对齐播放时封面的右边缘。
                val favoriteLeft = x(.0716f) + coverSide - buttonSize + with(density) { PLAYER_FAVORITE_EDGE_INSET.roundToPx() }
                val informationWidth = (favoriteLeft - informationLeft - with(density) { 8.dp.roundToPx() })
                    .coerceAtLeast(1)
                val title = children[1].measure(Constraints(maxWidth = informationWidth))
                val artists = children[2].measure(Constraints(maxWidth = informationWidth))
                val favorite = children[3].measure(Constraints.fixed(buttonSize, buttonSize))
                val informationGeometry = phonePlayerInformationGeometry(title.height, 0f, density)
                val informationTop = minOf(y(.86f) - title.height / 2,
                    h - informationGeometry.artistTop.roundToInt() - artists.height).coerceAtLeast(0)
                val lyricsLeft = x(.43f)
                val lyricsRight = w - with(density) { lyricsEndInset.roundToPx() }
                val lyricsWidth = lyricsColumn?.let {
                    phoneLandscapeLyricsWidth(it, with(density) { touchPadding.toPx() })
                } ?: (lyricsRight - lyricsLeft).coerceAtLeast(1)
                val lyrics = children[4].measure(Constraints.fixed(lyricsWidth,
                    with(density) { touchHeight.roundToPx() }))
                lyricsTravel = maxOf(lyrics.height.toFloat(), physicalWindow.width.toFloat(), 1f)
                // 时钟拥有独立中心和测量宽度，不随歌词窗口位置变化。
                val clock = children[5].measure(Constraints.fixed(w, y(.65f)))
                val progress = children[6].measure(Constraints.fixed(x(.86f), with(density) { 64.dp.roundToPx() }))
                layout(w, h) {
                    // 与实际放置共用本帧尺寸和暂停缩放，一批登记封面、标题和歌手，避免逐元素回调互相抢占。
                    val scale = playingScale.value
                    val coverBounds = Rect(x(.0716f).toFloat(), y(.067f).toFloat(),
                        x(.0716f) + coverSide.toFloat(), y(.067f) + coverSide.toFloat())
                    val coverHalf = Offset(coverSide * scale / 2f, coverSide * scale / 2f)
                    anchorLayout.size = IntSize(w, h)
                    anchorLayout.localAnchors = mapOf(
                        "cover" to MotionAnchor(Rect(coverBounds.center - coverHalf, coverBounds.center + coverHalf),
                            corner = 12f * scale, markSize = canvasWidthDp * .1f * scale, contentScale = scale),
                        "title" to MotionAnchor(Rect(informationLeft.toFloat(), informationTop.toFloat(),
                            informationLeft + title.width.toFloat(), informationTop + title.height.toFloat()),
                            textSizeSp = CompactPlayerLayoutSpec.titleSize.value),
                        "subtitle" to MotionAnchor(Rect(informationLeft.toFloat(), informationTop + informationGeometry.artistTop.roundToInt().toFloat(),
                            informationLeft + artists.width.toFloat(), informationTop + informationGeometry.artistTop.roundToInt() + artists.height.toFloat()),
                            textSizeSp = CompactPlayerLayoutSpec.artistSize.value))
                    // 信息区固定在封面下方，内部纵向间距与竖屏共用；屏幕锚点不参与静态排版。
                    progress.place(x(.07f), h - progress.height, zIndex = 1f)
                    cover.place(x(.0716f), y(.067f), zIndex = 2f)
                    title.place(informationLeft, informationTop, zIndex = 2f)
                    artists.place(informationLeft, informationTop + informationGeometry.artistTop.roundToInt(), zIndex = 2f)
                    favorite.place(favoriteLeft, informationTop + informationGeometry.favoriteTop.roundToInt(), zIndex = 2f)
                    // 此处只是空槽；正文列宽与竖屏一致，关闭时也登记横屏高度以提前准备阅读位置。
                    // 正文仍由独立宿主沿原位移隐藏，交互资格保持关闭。
                    lyrics.place(lyricsLeft, 0)
                    // 时钟由物理窗口的常驻宿主绘制，此占位仅保持原测量索引。
                }
            }
            }
        }
    }
}
