package com.musicone

import com.musicone.sourceintegration.sourceAnchorKey
import com.musicone.sourceintegration.usesGlass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal class BottomBarMeasurements {
    var miniHeight by mutableStateOf(78.dp)
    var navigationHeight by mutableStateOf(70.dp)
    var systemHeight by mutableStateOf(0.dp)
    /** 设置里关闭了页面切换胶囊时，根页面不再给它留底部空间。 */
    var navigationEnabled by mutableStateOf(true)
    val homeInset get() = miniHeight + (if (navigationEnabled) navigationHeight else 0.dp) + systemHeight
    val detailInset get() = miniHeight + systemHeight
}

@Composable
internal fun BottomPlayerBar(
    viewModel: MusicPlaybackRuntime,
    playlistMotion: PageMotion,
    playerMotion: PageMotion,
    measurements: BottomBarMeasurements,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    controlHandoff: PlayerControlHandoff,
    rootNavigation: RootPageNavigation,
    entities: EntityNavigation,
    entitySceneLayer: GraphicsLayer,
    entitySceneBounds: Rect,
    navigationVisible: Boolean,
    animatePlayerTransitions: Boolean,
    publicPageMounted: Boolean = false,
    immersiveVisual: SourceBackdropVisual? = null,
    sourcePlayerOpening: com.musicone.source.sdk.SourcePlayerOpening? = null,
    modifier: Modifier = Modifier,
    searchProgress: () -> Float = { 0f },
) {
    val state by rememberBottomPlayerPresentation(viewModel.state, viewModel.rapidTrackSwitch.presentation)
    val favorites = LocalMusicFavorites.current
    val toggleFavorite: (MusicTrack) -> Unit = { displayed ->
        val latest = viewModel.state.value.currentTrack
        favorites.toggle(latest?.let { playerVisualActionTrack(it, displayed) } ?: displayed)
    }
    val density = LocalDensity.current
    val miniBackdrop = playerTransitionBackdrop(entities.pages.isNotEmpty() || publicPageMounted, backdropLayer, backdropBounds,
        entitySceneLayer, entitySceneBounds)
    val miniBackdropLayer = miniBackdrop.layer
    val miniBackdropBounds = miniBackdrop.bounds
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val phonePresentation = LocalPhonePlayerPresentation.current
    // 与自建播放页同一判断：手机横屏页面不画玻璃，迷你条也不让位。
    val sourcePlayerRotation = if (phonePresentation?.isPhone == true) phonePresentation.latestRotation else 0
    SideEffect { measurements.systemHeight = systemBottom }
    val openPlayer = {
        // 先进入准备阶段再切换页面状态，避免展开页抢先显示一帧封面。
        phonePresentation?.prepareOpen()
        controlHandoff.begin(state.isPlaying)
        playerMotion.request(true)
        viewModel.setPlayerExpanded(true)
    }
    val openGesture = Modifier.miniPlayerOpenGesture(playerMotion,
        onStart = {
            phonePresentation?.prepareOpen()
            controlHandoff.begin(state.isPlaying)
            viewModel.setPlayerExpanded(true)
        },
        onExpandedChange = viewModel::setPlayerExpanded,
    )
    val style = rememberBottomBarStyle()
    SideEffect {
        MiniPlayerSurfaceCorner.dp = style.surfaceCorner.value
        MiniPlayerSurfaceTint.light = style.lightTint
    }
    PlayerScreenCornerEffect()
    // 两种布局共用同一个胶囊显隐进度：经典用它收起高度，并排用它让出宽度。
    val capsuleEnabled = !ExperiencePreferences.options.hideNavigationCapsule
    SideEffect { measurements.navigationEnabled = capsuleEnabled }
    val currentNavigationVisible by rememberUpdatedState(navigationVisible && capsuleEnabled)
    val currentSearchProgress by rememberUpdatedState(searchProgress)
    // 播放列表、搜索、设置等盖住根页面的程度；底栏上的胶囊和多选操作条都随它让位。
    val rootCoverage = remember(playlistMotion) {
        { (1f - maxOf(playlistMotion.value, currentSearchProgress())).coerceIn(0f, 1f) }
    }
    val baseNavigationVisibility = remember(rootCoverage) {
        { if (currentNavigationVisible) rootCoverage() else 0f }
    }
    // 歌单卡片多选：前半段导航胶囊沉下去，后半段玻璃操作条升上来；退出时倒放。
    val selectionHost = LocalPlaylistSelectionBarHost.current
    val selectionRequest = selectionHost.request
    // 退出时保留最后一份内容，等操作条完全沉下去再卸下。
    var retainedSelection by remember { mutableStateOf<PlaylistSelectionBarRequest?>(null) }
    SideEffect { if (selectionRequest != null) retainedSelection = selectionRequest }
    val shownSelection = selectionRequest ?: retainedSelection
    val selectionMotion = animateFloatAsState(if (selectionRequest != null) 1f else 0f, musicMotion(460), label = "底栏多选交接")
    LaunchedEffect(selectionRequest) {
        if (selectionRequest == null) {
            snapshotFlow { selectionMotion.value }.first { it <= 0f }
            retainedSelection = null
        }
    }
    // 经典布局操作条占胶囊原位，必须先沉后升；并排时操作条另起一行，胶囊让宽用满全程，
    // 否则让宽、播放条拉满和按钮展开挤在减速曲线最快的前半段里，显得很急。
    val classicSelection = style.classic
    val capsuleSink = remember(selectionMotion, classicSelection) {
        if (classicSelection) { { (selectionMotion.value / .5f).coerceIn(0f, 1f) } } else { { selectionMotion.value } }
    }
    val barRise = remember(selectionMotion, rootCoverage, classicSelection) {
        val start = if (classicSelection) .4f else .2f
        { ((selectionMotion.value - start) / (1f - start)).coerceIn(0f, 1f) * rootCoverage() }
    }
    val navigationVisibility = remember(baseNavigationVisibility, capsuleSink) {
        { baseNavigationVisibility() * (1f - capsuleSink()) }
    }
    val selectionBar: @Composable (Modifier) -> Unit = { barModifier ->
        shownSelection?.let { request ->
            PlaylistSelectionGlassBar(request, backdropLayer, backdropBounds, immersiveVisual, barModifier)
        }
    }
    // 队列被全部移出：迷你条先留着最后一首，等播放页和队列抽屉都离开后再滑走。
    val emptiedExit = remember { QueueEmptiedExit() }
    emptiedExit.update(state.currentTrack, !state.hasQueue)
    val queueState = LocalPlayerQueueState.current
    val holdingEmptied = emptiedExit.leaving && (playerMotion.mounted || queueState.visible)
    LaunchedEffect(emptiedExit.leaving, holdingEmptied) {
        // 退场动画结束后才放掉最后一首，退出途中内容不会变空。
        if (emptiedExit.leaving && !holdingEmptied) { delay(MiniExitMillis + 100L); emptiedExit.finish() }
    }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val miniSlot: @Composable () -> Unit = {
        Box(Modifier.onSizeChanged { measurements.miniHeight = with(density) { it.height.toDp() } }
            .graphicsLayer { alpha = if (sourcePlayerOpening?.barParticipates == true) 1f - playerMotion.value else 1f }) {
            val track = state.currentTrack ?: emptiedExit.lastTrack.takeIf { emptiedExit.leaving && animatePlayerTransitions }
            if (animatePlayerTransitions) {
                // 经典布局从屏幕底部进出；并排布局向外侧（反转时向右）滑出屏幕，胶囊随同一进度回到中间。
                val outward = if (style.mirrored) screenWidthPx else -screenWidthPx
                androidx.compose.animation.AnimatedVisibility(
                    visible = (state.currentTrack != null && state.hasQueue) || holdingEmptied,
                    enter = if (style.classic) slideInVertically(animationSpec = musicMotion(MiniEnterMillis), initialOffsetY = { it })
                        else slideInHorizontally(animationSpec = musicMotion(MiniEnterMillis), initialOffsetX = { outward }),
                    exit = if (style.classic) slideOutVertically(animationSpec = musicMotion(MiniExitMillis), targetOffsetY = { it })
                        else slideOutHorizontally(animationSpec = musicMotion(MiniExitMillis), targetOffsetX = { outward }),
                    modifier = Modifier.blockTouches(state.currentTrack == null),
                ) {
                    track?.let {
                        MiniPlayer(it, controlHandoff.displayedPlaying(state.isPlaying), it.id in favorites.state.ids,
                            miniBackdropLayer, miniBackdropBounds,
                            immersiveVisual,
                            onOpen = openPlayer, onToggle = viewModel::togglePlay,
                            onNext = viewModel::requestNext, onFavorite = { toggleFavorite(it) },
                            previewSwipe = viewModel::previewMiniSwipe, commitSwipe = viewModel::commitMiniSwipe,
                            openGesture = openGesture)
                    }
                }
            } else {
                track?.let {
                    MiniPlayer(it, controlHandoff.displayedPlaying(state.isPlaying), it.id in favorites.state.ids,
                        miniBackdropLayer, miniBackdropBounds,
                        immersiveVisual,
                        onOpen = openPlayer, onToggle = viewModel::togglePlay,
                        onNext = viewModel::requestNext, onFavorite = { toggleFavorite(it) },
                        previewSwipe = viewModel::previewMiniSwipe, commitSwipe = viewModel::commitMiniSwipe,
                        openGesture = openGesture)
                }
            }
        }
    }
    val capsuleSlot: @Composable (NavigationCapsuleMetrics?) -> Unit = { capsuleMetrics ->
        val queueState = LocalPlayerQueueState.current
        BottomCapsule(
            page = state.page,
            backdropLayer = backdropLayer,
            backdropBounds = backdropBounds,
            selectionPosition = { rootNavigation.position },
            onSelectionDrag = rootNavigation::dragTo,
            onPageChange = rootNavigation::animateTo,
            onNavigationContact = rootNavigation::beginNavigationInteraction,
            navigationDragBlocked = rootNavigation::isNavigationDragBlocked,
            queueState = queueState,
            immersiveVisual = immersiveVisual,
            capsuleMetrics = capsuleMetrics,
        )
    }
    CompositionLocalProvider(
        LocalSourcePlayerBarMotionEnabled provides (sourcePlayerOpening == null),
        LocalSourcePlayerBarHandoff provides sourcePlayerOpening?.takeIf { !it.pageFade }?.let { opening ->
            SourcePlayerBarHandoff(opening.sharedElements.mapTo(mutableSetOf()) {
                it.sourceAnchorKey()
            }, opening.sharedOnClose, opening.usesGlass(sourcePlayerRotation))
        },
        LocalBottomBarStyle provides style,
    ) {
    if (style.classic) {
        // 与并排布局同宽：宽屏上不撑满，整列居中。
        Box(modifier.navigationBarsPadding().fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.widthIn(max = BottomBarMaxWidth).fillMaxWidth()) {
            miniSlot()
            // 多选期间槽位保持高度，胶囊在槽内沉下去，操作条在同一位置升起；本地音乐没有胶囊时由操作条撑开槽位。
            Box(Modifier.clipToBounds().layout { measurable, constraints ->
                val child = measurable.measure(constraints.copy(minHeight = 0))
                val visibility = maxOf(baseNavigationVisibility(), barRise())
                val height = (child.height * visibility).roundToInt().coerceAtLeast(0)
                layout(child.width, height) { child.placeRelative(0, (child.height * (1f - visibility)).roundToInt()) }
            }) {
                Box(Modifier.onSizeChanged { measurements.navigationHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer {
                        alpha = navigationVisibility()
                        translationY = size.height * .5f * capsuleSink()
                    }.blockTouches(selectionRequest != null)) {
                    capsuleSlot(null)
                }
                selectionBar(Modifier.align(Alignment.Center).padding(horizontal = 12.dp).graphicsLayer {
                    alpha = barRise()
                    translationY = size.height * .6f * (1f - barRise())
                })
            }
        }
        }
    } else {
        val phone = PhonePlayerOrientationPolicy.isPhone(LocalConfiguration.current.smallestScreenWidthDp)
        val rowMetrics = rowNavigationCapsuleMetrics(phone)
        val barPresent = (state.currentTrack != null && (!animatePlayerTransitions || state.hasQueue)) || holdingEmptied
        val presence = animateFloatAsState(if (barPresent) 1f else 0f,
            musicMotion(if (barPresent) MiniEnterMillis else MiniExitMillis), label = "底栏播放条占位")
        var capsuleHeight by remember { mutableStateOf(0.dp) }
        // 同一行里胶囊不再单独占高度，只有没有播放条时才由它撑起根页面的底部留白。
        val rowNavigationHeight = (capsuleHeight - measurements.miniHeight).coerceAtLeast(0.dp)
        SideEffect { measurements.navigationHeight = rowNavigationHeight }
        // 手机上收藏与下一首随胶囊让出的宽度出现；平板空间充裕，始终显示。
        val accessoryReveal: (() -> Float)? =
            if (phone) remember(navigationVisibility) { { 1f - navigationVisibility() } } else null
        CompositionLocalProvider(LocalMiniAccessoryReveal provides accessoryReveal) {
            // 并排时胶囊没有单独的一行：它向外让出宽度，操作条从播放条后方升到播放条上方。
            Column(modifier.navigationBarsPadding()) {
                if (shownSelection != null) Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .graphicsLayer {
                        alpha = barRise()
                        translationY = (size.height + 8.dp.toPx()) * (1f - barRise())
                    }, contentAlignment = Alignment.Center) {
                    selectionBar(Modifier)
                }
                BottomPlayerBarRow(
                    mirrored = style.mirrored,
                    capsuleWidth = rowMetrics.width,
                    capsuleVisibility = navigationVisibility,
                    miniPresence = { presence.value },
                    mini = miniSlot,
                    capsule = {
                        Box(Modifier.onSizeChanged { capsuleHeight = with(density) { it.height.toDp() } }
                            .blockTouches(selectionRequest != null)) {
                            capsuleSlot(rowMetrics)
                        }
                    },
                )
            }
        }
    }
    }
}

/** 多选期间让位的导航胶囊仍在原处布局，先在外层截下触摸，避免误切页面。 */
private fun Modifier.blockTouches(blocked: Boolean): Modifier = if (!blocked) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** 自建页的底栏统一显隐，内部控件不再单独执行原生共享动画。 */
internal val LocalSourcePlayerBarMotionEnabled = staticCompositionLocalOf { true }

/** 迷你条进出时长：经典的上下滑动、并排的左右滑动与胶囊归位共用。 */
private const val MiniEnterMillis = 480
private const val MiniExitMillis = 320
