package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QqProfileHeader(account: MusicAccount?, onLogin: () -> Unit, expanded: Boolean = false) {
    val entry = LocalAccountEntry.current ?: onLogin
    Row(
        Modifier.fillMaxWidth().heightIn(min = 82.dp).clickable(enabled = account == null, onClick = entry),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(account = account, size = if (expanded) 96.dp else 72.dp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                account?.nickname?.ifBlank { "${account.source.label}用户" } ?: "选择音源并登录",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (account == null) "同步我喜欢与歌单" else account.source.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun QqFavoritesRow(
    playlist: MusicPlaylist?,
    artworkVersion: Long,
    signedIn: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val artworkUrl = playlist?.artworkUrl
    val requestedArtwork by rememberArtworkBitmap(artworkUrl, artworkVersion.takeIf { it > 0L })
    val artwork = if (artworkUrl.isNullOrBlank()) requestedArtwork else ArtworkRepository.peek(artworkUrl)
    LaunchedEffect(playlist?.id, artwork) {
        playlist?.let { transition?.updateArtwork(it.id, artwork) }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable(
            enabled = transition?.busy != true && motion?.mounted != true,
            onClickLabel = "打开我喜欢的音乐",
        ) {
            if (playlist == null || transition == null) onClick()
            else transition.open(playlist.id, artwork, {}, onClick)
        },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CrossfadingArtworkBitmapOrPlaceholder(
                artwork,
                artworkUrl?.isNotBlank() == true && artwork == null,
                playlist?.artworkStart ?: 0xFFB7D8CC,
                playlist?.artworkEnd ?: 0xFF5D8F7E,
                playlist?.artworkMark ?: "喜",
                Modifier.size(52.dp).motionAnchor(
                    motion.takeIf { playlist != null },
                    playlist?.id.orEmpty(),
                    false,
                    corner = 16f,
                    markSize = 20f,
                    markX = 0f,
                    markY = 0f,
                ),
                20.sp,
                RoundedCornerShape(16.dp),
                Alignment.Center,
            )
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp).graphicsLayer {
                    alpha = playlist?.let { transition?.alphaFor(it.id) } ?: 1f
                },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("我喜欢的音乐", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !signedIn -> "登录后查看"
                        playlist != null -> "${playlist.count} 首"
                        else -> ""
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Box(Modifier.graphicsLayer {
                alpha = playlist?.let { transition?.alphaFor(it.id) } ?: 1f
            }) {
                Icon(Icons.Default.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun QqPlaylistSegment(
    selectedCreated: Boolean,
    createdCount: Int,
    collectedCount: Int,
    onCreated: () -> Unit,
    onCollected: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QqSegmentButton("创建的 $createdCount", selectedCreated, onCreated, Modifier.weight(1f))
        QqSegmentButton("收藏的 $collectedCount", !selectedCreated, onCollected, Modifier.weight(1f))
    }
}

@Composable
private fun QqSegmentButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val background by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer,
        musicMotion(320), label = "歌单分区底色")
    val foreground by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        musicMotion(320), label = "歌单分区文字")
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(16.dp),
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = foreground,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun QqLibraryPlaylistRow(playlist: MusicPlaylist, artworkVersion: Long, onClick: () -> Unit,
    expanded: Boolean = false) {
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val requestedArtwork by rememberArtworkBitmap(
        playlist.artworkUrl,
        artworkVersion.takeIf { it > 0L },
    )
    val artwork = if (playlist.artworkUrl.isNullOrBlank()) {
        requestedArtwork
    } else {
        ArtworkRepository.peek(playlist.artworkUrl)
    }
    LaunchedEffect(playlist.id, artwork) { transition?.updateArtwork(playlist.id, artwork) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(
            enabled = transition?.busy != true && motion?.mounted != true,
            onClickLabel = "打开${playlist.title}",
        ) {
            if (transition == null) onClick()
            else transition.open(playlist.id, artwork, {}, onClick)
        }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrossfadingArtworkBitmapOrPlaceholder(
            artwork,
            playlist.artworkUrl?.isNotBlank() == true && artwork == null,
            playlist.artworkStart,
            playlist.artworkEnd,
            playlist.artworkMark,
            Modifier.size(if (expanded) 96.dp else 64.dp).motionAnchor(
                motion,
                playlist.id,
                false,
                corner = 16f,
                markSize = 24f,
                markX = 0f,
                markY = 0f,
            ),
            24.sp,
            RoundedCornerShape(16.dp),
            Alignment.Center,
        )
        Column(
            Modifier.weight(1f).padding(horizontal = 13.dp).graphicsLayer {
                alpha = transition?.alphaFor(playlist.id) ?: 1f
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(playlist.title, fontSize = if (expanded) 17.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${playlist.count} 首", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).graphicsLayer {
                alpha = transition?.alphaFor(playlist.id) ?: 1f
            })
    }
}

@Composable
internal fun QqLibraryPlaylistCard(
    playlist: MusicPlaylist,
    artworkVersion: Long,
    supportText: String = "${playlist.count} 首",
    reserveTwoTitleLines: Boolean = false,
    sourceKey: String = playlist.id,
    onClick: () -> Unit,
) {
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val requested by rememberArtworkBitmap(playlist.artworkUrl, artworkVersion.takeIf { it > 0L })
    // 直接观察异步加载结果，不能等点击触发重组后才显示封面。
    val artwork = requested ?: ArtworkRepository.peek(playlist.artworkUrl)
    LaunchedEffect(sourceKey, artwork) { transition?.updateArtwork(sourceKey, artwork) }
    Column(
        Modifier.fillMaxWidth().clickable(
            enabled = transition?.busy != true && motion?.mounted != true,
            onClickLabel = "打开${playlist.title}",
        ) { if (transition == null) onClick() else transition.open(sourceKey, artwork, {}, onClick) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CrossfadingArtworkBitmapOrPlaceholder(
            artwork, playlist.artworkUrl?.isNotBlank() == true && artwork == null,
            playlist.artworkStart, playlist.artworkEnd, playlist.artworkMark,
            Modifier.fillMaxWidth().aspectRatio(1f).motionAnchor(
                motion, sourceKey, false, corner = 20f, markSize = 48f, markX = 0f, markY = 0f,
            ),
            48.sp, RoundedCornerShape(20.dp), Alignment.Center,
        )
        Text(playlist.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            minLines = if (reserveTwoTitleLines) 2 else 1, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer {
                alpha = transition?.alphaFor(sourceKey) ?: 1f
            })
        Text(supportText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { alpha = transition?.alphaFor(sourceKey) ?: 1f })
    }
}
