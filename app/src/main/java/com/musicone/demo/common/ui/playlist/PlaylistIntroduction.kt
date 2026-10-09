package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect

@Composable
internal fun PlaylistCover(
    playlist: MusicPlaylist,
    modifier: Modifier = Modifier,
    usePageBackground: Boolean = false,
) {
    val pageColor = PlaylistPageColor
    val track = playlist.tracks.firstOrNull()
    BoxWithConstraints(modifier) {
        val markSize = minOf(120f, maxHeight.value * .38f).sp
        val motion = LocalPlaylistMotion.current
        val artworkModifier = Modifier.fillMaxSize().motionAnchor(motion, motion?.coverKey ?: playlist.id, true, markSize = markSize.value, markX = 0f, markY = 0f, bottomFade = 1f)
        val imageUrl = playlist.artworkUrl ?: track?.artworkUrl
        val requestedArtwork by rememberArtworkBitmap(imageUrl.takeUnless { usePageBackground })
        val artwork = requestedArtwork ?: LocalPlaylistCardTransition.current?.activeArtwork.takeUnless { usePageBackground }
        // 沉浸背景模式只保留转场锚点，不再把首歌封面或不透明渐隐盖到页面上。
        val coverModifier = if (usePageBackground) artworkModifier else artworkModifier.drawWithContent {
            drawContent()
            drawPlaylistArtworkFade(Rect(0f, 0f, size.width, size.height), 1f, pageColor)
        }
        Box(coverModifier) {
            if (!usePageBackground) {
                PlaylistSyncedArtwork(
                    artwork,
                    imageUrl?.isNotBlank() == true && artwork == null,
                    track?.artworkStart ?: playlist.artworkStart,
                    track?.artworkEnd ?: playlist.artworkEnd,
                    track?.artworkMark ?: playlist.artworkMark,
                    Modifier.fillMaxSize(),
                    markSize,
                    RectangleShape,
                    Alignment.Center,
                    sourceKey = motion?.coverKey ?: playlist.id, imageUrl = imageUrl,
                )
            }
        }
    }
}

@Composable
internal fun PlaylistIntroduction(
    playlist: MusicPlaylist,
    sort: PlaylistSort,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSort: (PlaylistSort) -> Unit,
    fixedLayout: Boolean = false,
    showPlaybackActions: Boolean = true,
    showDescription: Boolean = true,
    horizontalPadding: Dp = 24.dp,
    titleMaxLines: Int = Int.MAX_VALUE,
    hasCustomBackground: Boolean = false,
    onChooseCustomBackground: (() -> Unit)? = null,
    onRestoreInheritedBackground: (() -> Unit)? = null,
) {
    var backgroundDialogOpen by remember { mutableStateOf(false) }
    val slots = LocalTabletPlaylistTextSlots.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).playlistDetailReveal(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(musicOneEditorialAnnotatedString(playlist.title), style = MusicOneTextStyles.editorialTitle,
            modifier = if (slots == null) Modifier else Modifier.height(slots.title).wrapContentHeight(Alignment.Bottom),
            textAlign = TextAlign.Center, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
        if (playlist.isQqSearchAlbum) Text("专辑", fontSize = 20.sp, lineHeight = 26.sp,
            modifier = if (slots == null) Modifier else Modifier.height(slots.album), textAlign = TextAlign.Center)
        Text(playlist.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = if (slots == null) Modifier else Modifier.height(slots.subtitle),
            lineHeight = if (slots == null) androidx.compose.ui.unit.TextUnit.Unspecified else 18.sp,
            maxLines = if (slots == null) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
        if (onChooseCustomBackground != null) {
            TextButton(onClick = { if (hasCustomBackground) backgroundDialogOpen = true else onChooseCustomBackground() }) {
                Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (hasCustomBackground) "背景设置" else "自定义背景")
            }
        }
        if (showPlaybackActions) PlaylistPlaybackActions(sort, playlist.tracks.isNotEmpty(), onPlay, onShuffle, onSort, playlist.source)
        if (showDescription) PlaylistDescription(playlist.description, fixedLayout, playlist.isQqSearchAlbum)
    }
    if (backgroundDialogOpen) {
        AlertDialog(
            onDismissRequest = { backgroundDialogOpen = false },
            title = { Text("歌单背景") },
            text = { Text("可以重新裁剪一张图片，或恢复为“我的”页面使用的全局背景。") },
            confirmButton = {
                TextButton(onClick = {
                    backgroundDialogOpen = false
                    onChooseCustomBackground?.invoke()
                }) { Text("更换图片") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        backgroundDialogOpen = false
                        onRestoreInheritedBackground?.invoke()
                    }) { Text("恢复全局背景") }
                    TextButton(onClick = { backgroundDialogOpen = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
internal fun PlaylistPlaybackActions(
    sort: PlaylistSort,
    hasTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSort: (PlaylistSort) -> Unit,
    source: MusicSource,
) {
    Row(
        Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = onShuffle, enabled = hasTracks, modifier = Modifier.size(48.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f), contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Icon(Icons.Default.Shuffle, contentDescription = "随机播放") }
        Button(
            onClick = onPlay, enabled = hasTracks, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text("播放", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (source == MusicSource.QQ) QqPlaylistSortButton(sort, onSort) else PlaylistSortButton(sort, onSort)
    }
}

@Composable
internal fun PlaylistDescription(description: String, showInDialog: Boolean, album: Boolean = false,
    centered: Boolean = false) {
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    var overflows by remember(description) { mutableStateOf(false) }
    val slots = LocalTabletPlaylistTextSlots.current
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            description, fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded && !showInDialog) Int.MAX_VALUE else 2,
            minLines = if (slots == null) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        Box(Modifier.align(Alignment.End).then(if (slots == null) Modifier else Modifier.height(slots.more))) {
            if (overflows || expanded) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "更多", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (expanded && showInDialog) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(if (album) "专辑简介" else "歌单简介") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item { Text(description, fontSize = 15.sp, lineHeight = 23.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } },
        )
    }
}
