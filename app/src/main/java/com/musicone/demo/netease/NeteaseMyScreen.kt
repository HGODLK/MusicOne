package com.musicone.demo

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class NeteaseProfileVisual(
    val bitmap: Bitmap?,
    val foreground: Color,
    val secondary: Color,
    val tertiary: Color,
    val panelColor: Color,
    val panelBorder: Color,
    val protectionColor: Color,
    val useDarkForeground: Boolean,
)

/** 只向需要沉浸背景的页面传递，避免污染设置、搜索等普通页面的字体颜色。 */
internal val LocalNeteaseGlobalVisual = staticCompositionLocalOf<NeteaseProfileVisual?> { null }

@Composable
internal fun NeteaseAdaptiveForeground(
    visual: NeteaseProfileVisual?,
    content: @Composable () -> Unit,
) {
    if (visual == null) {
        content()
        return
    }
    val readableSurface = if (visual.useDarkForeground) Color(0xFFF7F7F8) else Color(0xFF1D1F23)
    val readableSurfaceVariant = if (visual.useDarkForeground) Color(0xFFE7E8EA) else Color(0xFF303238)
    val colors = MaterialTheme.colorScheme.copy(
        surface = readableSurface,
        surfaceVariant = readableSurfaceVariant,
        surfaceContainer = readableSurface,
        surfaceContainerHigh = readableSurfaceVariant,
        onBackground = visual.foreground,
        onSurface = visual.foreground,
        onSurfaceVariant = visual.secondary,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides visual.foreground, content = content)
    }
}

@Composable
internal fun rememberNeteaseProfileVisual(
    customBackgroundUri: String? = null,
    customBackgroundRevision: Int = 0,
): NeteaseProfileVisual {
    val imageUrl = neteaseProfileBackgroundSource(customBackgroundUri)
    val bitmap by rememberArtworkBitmap(
        imageUrl,
        refreshKey = customBackgroundRevision.takeIf { customBackgroundUri != null && it > 0 },
        maxSide = 1_600,
    )
    val fallbackLuminance = MaterialTheme.colorScheme.background.luminance()
    val luminance = remember(bitmap, fallbackLuminance) {
        bitmap?.profileAverageLuminance() ?: fallbackLuminance
    }
    val useDarkForeground = luminance >= .62f
    val targetForeground = if (useDarkForeground) Color(0xFF101318) else Color.White
    val targetPanel = if (useDarkForeground) Color.White.copy(alpha = .38f)
        else Color.Black.copy(alpha = .3f)
    val targetProtection = if (useDarkForeground) Color.White else Color.Black
    val foreground by animateColorAsState(targetForeground, musicMotion(420), label = "背景文字对比度")
    val panelColor by animateColorAsState(targetPanel, musicMotion(420), label = "背景卡片对比度")
    val protectionColor by animateColorAsState(targetProtection, musicMotion(420), label = "背景保护层")
    return remember(bitmap, foreground, panelColor, protectionColor, useDarkForeground) {
        NeteaseProfileVisual(
            bitmap = bitmap,
            foreground = foreground,
            secondary = foreground.copy(alpha = .78f),
            tertiary = foreground.copy(alpha = .6f),
            panelColor = panelColor,
            panelBorder = foreground.copy(alpha = .18f),
            protectionColor = if (useDarkForeground) Color.White else Color.Black,
            useDarkForeground = useDarkForeground,
        )
    }
}

@Composable
internal fun NeteaseProfileBackdrop(
    visual: NeteaseProfileVisual,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Crossfade(
            targetState = visual.bitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = musicMotion(450),
            label = "我的页背景交接",
        ) { bitmap ->
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .16f else .3f),
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .08f else .16f),
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .24f else .48f),
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .48f else .68f),
                    ),
                ),
            ),
        )
    }
}

@Composable
internal fun NeteaseMyScreen(
    account: MusicAccount?,
    catalog: MusicCatalogUiState,
    visual: NeteaseProfileVisual,
    bottomInset: Dp,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onRefreshLibrary: () -> Unit,
    hasCustomBackground: Boolean,
    onChooseCustomBackground: () -> Unit,
    onRestoreDefaultBackground: () -> Unit,
) {
    var backgroundDialogOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportPrimaryHeaderScroll(MusicOnePage.MY, listState)
    val liked = catalog.libraryPlaylists.firstOrNull { it.title.contains("喜欢") }
        ?: catalog.libraryPlaylists.firstOrNull()
    CompositionLocalProvider(LocalContentColor provides visual.foreground) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 88.dp,
                bottom = bottomInset + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("profile") {
                NeteaseProfileHeader(account, catalog.libraryPlaylists.size, visual)
            }
            item("shortcuts") {
                NeteaseProfileShortcuts(
                    visual = visual,
                    onLiked = liked?.let { playlist -> { onOpenPlaylist(playlist) } },
                    onOpenBackground = { backgroundDialogOpen = true },
                )
            }
            item("music-title") {
                NeteaseLibraryHeading(visual, catalog.loadingLibrary, onRefreshLibrary)
            }
            when {
                catalog.loadingLibrary && catalog.libraryPlaylists.isEmpty() -> item("library-loading") {
                    NeteaseLibraryStatus("正在加载你的网易云歌单…", visual, loading = true)
                }
                catalog.libraryPlaylists.isEmpty() -> item("library-empty") {
                    NeteaseLibraryStatus(
                        catalog.libraryMessage ?: "还没有可显示的歌单",
                        visual,
                        loading = false,
                    )
                }
                else -> items(catalog.libraryPlaylists, key = MusicPlaylist::id) { playlist ->
                    NeteasePlaylistRow(playlist, visual) { onOpenPlaylist(playlist) }
                }
            }
            catalog.libraryMessage?.takeIf { catalog.libraryPlaylists.isNotEmpty() }?.let { message ->
                item("library-message") { NeteaseLibraryStatus(message, visual, loading = false) }
            }
        }
    }
    if (backgroundDialogOpen) {
        AlertDialog(
            onDismissRequest = { backgroundDialogOpen = false },
            title = { Text("个性背景") },
            text = { Text("选择一张图片后会复制到应用内，原图删除后仍可使用。文字颜色会根据背景自动调整。") },
            confirmButton = {
                TextButton(onClick = {
                    backgroundDialogOpen = false
                    onChooseCustomBackground()
                }) { Text("选择图片") }
            },
            dismissButton = {
                Row {
                    if (hasCustomBackground) {
                        TextButton(onClick = {
                            backgroundDialogOpen = false
                            onRestoreDefaultBackground()
                        }) { Text("恢复默认") }
                    }
                    TextButton(onClick = { backgroundDialogOpen = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun NeteaseProfileHeader(
    account: MusicAccount?,
    playlistCount: Int,
    visual: NeteaseProfileVisual,
) {
    val shadow = profileTextShadow(visual)
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(102.dp)
                .clip(CircleShape)
                .border(2.dp, visual.foreground.copy(alpha = .9f), CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            AccountAvatar(account = account, size = 92.dp)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = musicOneUiAnnotatedString(account?.nickname?.ifBlank { "网易云用户" } ?: "登录网易云音乐"),
                color = visual.foreground,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                style = TextStyle(fontWeight = FontWeight.SemiBold, shadow = shadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account?.hasVipAccess == true) {
                Surface(
                    modifier = Modifier.padding(start = 8.dp),
                    shape = RoundedCornerShape(50),
                    color = NeteaseMusicThemeColor.copy(alpha = .88f),
                    contentColor = Color.White,
                ) {
                    Text(
                        "VIP",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            text = account?.signature?.ifBlank { "让音乐留住此刻" } ?: "连接账号后同步你的音乐",
            modifier = Modifier.padding(top = 7.dp),
            color = visual.secondary,
            fontSize = 13.sp,
            style = TextStyle(shadow = shadow),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ProfileStat(formatProfileCount(account?.follows ?: 0), "关注", visual)
            ProfileStat(formatProfileCount(account?.followers ?: 0), "粉丝", visual)
            ProfileStat(formatProfileCount(playlistCount), "歌单", visual)
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, visual: NeteaseProfileVisual) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = visual.foreground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = profileTextShadow(visual)),
        )
        Text(
            label,
            color = visual.secondary,
            fontSize = 11.sp,
            style = TextStyle(shadow = profileTextShadow(visual)),
        )
    }
}

@Composable
private fun NeteaseProfileShortcuts(
    visual: NeteaseProfileVisual,
    onLiked: (() -> Unit)?,
    onOpenBackground: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        ProfileShortcut("最近", Icons.Default.History, visual, null, Modifier.weight(1f))
        ProfileShortcut("背景", Icons.Default.Wallpaper, visual, onOpenBackground, Modifier.weight(1f))
        ProfileShortcut("喜欢", Icons.Default.FavoriteBorder, visual, onLiked, Modifier.weight(1f))
    }
}

@Composable
private fun ProfileShortcut(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    visual: NeteaseProfileVisual,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = visual.panelColor,
        contentColor = visual.foreground,
        border = androidx.compose.foundation.BorderStroke(.75.dp, visual.panelBorder),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun NeteaseLibraryHeading(
    visual: NeteaseProfileVisual,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                musicOneUiAnnotatedString("我的音乐"),
                color = visual.foreground,
                fontSize = 25.sp,
                style = TextStyle(fontWeight = FontWeight.SemiBold, shadow = profileTextShadow(visual)),
            )
            Text(
                "网易云歌单",
                color = visual.secondary,
                fontSize = 11.sp,
                style = TextStyle(shadow = profileTextShadow(visual)),
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.semantics { contentDescription = "刷新我的歌单" },
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = visual.foreground, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = visual.foreground)
            }
        }
    }
}

@Composable
private fun NeteasePlaylistRow(
    playlist: MusicPlaylist,
    visual: NeteaseProfileVisual,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = visual.panelColor,
        contentColor = visual.foreground,
        border = androidx.compose.foundation.BorderStroke(.75.dp, visual.panelBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artwork by rememberArtworkBitmap(playlist.artworkUrl)
            PlaylistSyncedArtwork(
                bitmap = artwork,
                awaitingArtwork = playlist.artworkUrl?.isNotBlank() == true && artwork == null,
                imageUrl = playlist.artworkUrl,
                start = playlist.artworkStart,
                end = playlist.artworkEnd,
                mark = playlist.artworkMark,
                modifier = Modifier.size(58.dp),
                markSize = 20.sp,
                shape = RoundedCornerShape(13.dp),
                sourceKey = playlist.id,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    playlist.title,
                    color = visual.foreground,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${playlist.count} 首 · ${playlist.subtitle}",
                    modifier = Modifier.padding(top = 4.dp),
                    color = visual.secondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = visual.secondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun NeteaseLibraryStatus(
    message: String,
    visual: NeteaseProfileVisual,
    loading: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        color = visual.panelColor,
        contentColor = visual.foreground,
        border = androidx.compose.foundation.BorderStroke(.75.dp, visual.panelBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = visual.foreground, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = visual.secondary)
            }
            Text(message, color = visual.secondary, fontSize = 13.sp)
        }
    }
}

private fun Bitmap.profileAverageLuminance(): Float {
    val samples = ArtworkColorSampler.sampleBitmap(this)
    if (samples.isEmpty()) return 0f
    return samples.sumOf { Color(it).luminance().toDouble() }.div(samples.size).toFloat()
}

private fun profileTextShadow(visual: NeteaseProfileVisual): Shadow = Shadow(
    color = if (visual.useDarkForeground) Color.White.copy(alpha = .52f)
        else Color.Black.copy(alpha = .72f),
    blurRadius = 7f,
)

internal fun formatProfileCount(value: Int): String = when {
    value >= 100_000 -> "${value / 10_000}万+"
    value >= 10_000 -> "${(value / 1_000) / 10f}万"
    else -> value.coerceAtLeast(0).toString()
}
