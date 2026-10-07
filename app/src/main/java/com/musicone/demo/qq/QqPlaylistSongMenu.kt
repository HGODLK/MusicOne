package com.musicone.demo

import androidx.compose.material3.MaterialTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 歌曲菜单独立持有状态与采样层，不将写入和菜单动画塞进页面主组件。 */
@Composable
internal fun QqPlaylistSongMenuHost(
    playlist: MusicPlaylist,
    createdPlaylists: List<MusicPlaylist>,
    bottomInset: Dp,
    onRemoved: (MusicTrack) -> Unit,
    onChanged: (MusicPlaylist) -> Unit,
    onFavorite: (MusicTrack) -> Unit,
    addOnly: Boolean = false,
    relatedOnly: Boolean = false,
    avoidQueueRow: Boolean = false,
    liveBackdrop: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val menu = remember(playlist.id) { QqPlaylistSongMenuState(scope, PlatformPreferences(context)) }
    val frame = LocalEntityFrame.current
    val entities = LocalEntityNavigation.current
    SideEffect { if (!addOnly) { if (frame != null) frame.menu = menu else if (menu.expanded) entities?.rootMenu = menu } }
    DisposableEffect(menu) { onDispose { if (entities?.rootMenu === menu) entities.rootMenu = null } }
    val backdrop = rememberGraphicsLayer()
    val surface = rememberGraphicsLayer()
    val artistArtwork = remember(menu) { MenuArtistArtworkState() }
    SideEffect {
        menu.surfaceLayer = surface
        menu.artistArtworkState = artistArtwork
    }
    DisposableEffect(menu, artistArtwork) {
        onDispose {
            if (menu.artistArtworkState === artistArtwork) menu.artistArtworkState = null
        }
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    SideEffect { menu.backdropLayer = backdrop; menu.backdropBounds = bounds; menu.liveBackdrop = liveBackdrop }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalQqPlaylistSongMenu provides menu.takeIf { playlist.source == MusicSource.QQ },
        LocalMenuArtistArtwork provides artistArtwork) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, menu.expanded,
                captureContinuously = liveBackdrop)) { content() }
            val baseWidth = minOf(360.dp, maxWidth - 24.dp)
            val expandedWidth = minOf(360.dp, maxWidth - 24.dp)
            val availableHeight = (maxHeight - bottomInset - 84.dp).coerceAtLeast(100.dp)
            val width = baseWidth
            val height = if (relatedOnly) with(density) { menuSize.height.toDp() }.takeIf { it > 0.dp }
                ?: minOf(176.dp, availableHeight) else minOf(320.dp, availableHeight)
            val x = with(density) { (menu.anchor.right - bounds.left).toDp() - width }
                .coerceIn(12.dp, (maxWidth - width - 12.dp).coerceAtLeast(12.dp))
            val y = with(density) { (menu.anchor.top - bounds.top).toDp() - height - 8.dp }
                .coerceIn(60.dp, (maxHeight - bottomInset - height - 12.dp).coerceAtLeast(60.dp))
            val contentHeight = if (avoidQueueRow) maxHeight else if (relatedOnly) availableHeight else
                (maxHeight - bottomInset - y - 12.dp).coerceAtLeast(80.dp)
            val placement = if (avoidQueueRow) Modifier.queueSongMenuPlacement(menu.anchor, bounds) else Modifier.offset(x, y)
            AnimatedVisibility(menu.expanded,
                enter = fadeIn(musicMotion(220)) + if (avoidQueueRow) EnterTransition.None else scaleIn(musicMotion(280), initialScale = .84f,
                    transformOrigin = qualityMenuTransformOrigin(menu.anchor.center, bounds)),
                exit = fadeOut(musicMotion(180)) + if (avoidQueueRow) ExitTransition.None else scaleOut(musicMotion(220), targetScale = .92f,
                    transformOrigin = qualityMenuTransformOrigin(menu.anchor.center, bounds))) {
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { menu.dismiss() }) {
                    MusicOneBackdropGlass(
                        backdropLayer = backdrop, backdropBounds = bounds,
                        modifier = placement.onSizeChanged { menuSize = it }
                            .onGloballyPositioned { menu.surfaceBounds = it.boundsInRoot() }
                            .drawWithContent {
                                surface.record { this@drawWithContent.drawContent() }
                                drawLayer(surface)
                            }
                            .clickable(remember { MutableInteractionSource() }, null) {},
                        shape = RoundedCornerShape(28.dp), blurRadius = 24.dp,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                        fallbackColor = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface.copy(alpha = .2f)),
                    ) {
                        AnimatedContent(menu.choosing to menu.choosingArtist, label = "歌曲菜单展开收回",
                            transitionSpec = {
                                val direction = if (targetState.first || targetState.second) 1 else -1
                                ((fadeIn(musicMotion(240)) + slideInVertically(musicMotion(320)) { direction * it / 8 }) togetherWith
                                    (fadeOut(musicMotion(160)) + slideOutVertically(musicMotion(240)) { -direction * it / 8 }))
                                    .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> musicMotion(320) }))
                            }) { phase ->
                            val choosing = phase.first
                            Column(Modifier.width(if (choosing) expandedWidth else baseWidth)
                                .heightIn(max = contentHeight).verticalScroll(rememberScrollState()).padding(14.dp)
                                ) {
                                Text(if (choosing) "添加到歌单" else menu.track?.title.orEmpty(),
                                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(10.dp))
                                if (menu.busy) Text("正在处理…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                                menu.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)) }
                                if (choosing) {
                                    SongMenuRow("返回", enabled = !menu.busy) { menu.back() }
                                    menu.targets.forEach { target ->
                                        SongMenuRow(target.title, !menu.busy) {
                                            menu.change(target.id, true) { changed, _ -> onChanged(changed) }
                                        }
                                    }
                                    if (menu.targets.isEmpty() && !menu.busy) SongMenuRow("重新加载", true) { menu.choose() }
                                } else {
                                    val favorites = playlist.isQqFavoritesShortcut()
                                    val removable = favorites || createdPlaylists.any { it.id == playlist.id }
                                    if (!addOnly && !relatedOnly && removable && !phase.second) SongMenuRow(if (favorites) "取消收藏" else "移出歌单", !menu.busy) {
                                        if (favorites) { menu.track?.let(onFavorite); menu.dismiss() }
                                        else menu.change(playlist.id, false) { changed, track ->
                                            onRemoved(track)
                                            onChanged(changed)
                                        }
                                    }
                                    if (!relatedOnly && !phase.second) SongMenuRow("添加到", !menu.busy) { menu.choose() }
                                    if (!addOnly) RelatedEntityMenu(
                                        menu,
                                        phase.second,
                                        artworkVisible = phase == (menu.choosing to menu.choosingArtist),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            MenuArtistArtworkOverlay(artistArtwork, bounds, menu.expanded)
        }
    }
    // 在详情页之后注册，返回键优先收回子菜单，不直接关闭歌单。
    if (menu.expanded && (addOnly || relatedOnly || (LocalEntityActive.current && LocalPlayerMotion.current?.mounted != true))) BackHandler { menu.back() }
}

@Composable
internal fun SongMenuRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 15.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f), fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
