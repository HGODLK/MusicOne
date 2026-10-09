package com.musicone.demo

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QqPersonalizedCards(
    dailyPlaylist: MusicPlaylist,
    dailyDate: String? = null,
    dailyLoading: Boolean,
    dailyMessage: String?,
    radioActive: Boolean,
    radioPlaying: Boolean,
    radioLoading: Boolean,
    onDailyClick: () -> Unit,
    onRadioClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val cardHeight = ((maxWidth - 16.dp) / 2 / 1.35f).coerceIn(200.dp, 260.dp)
    Row(Modifier.fillMaxWidth().height(cardHeight), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        QqDailyCard(
            playlist = dailyPlaylist,
            date = dailyDate,
            loading = dailyLoading,
            message = dailyMessage,
            onClick = onDailyClick,
            modifier = Modifier.weight(1f),
        )
        QqRadioCard(
            active = radioActive,
            playing = radioPlaying,
            loading = radioLoading,
            onClick = onRadioClick,
            modifier = Modifier.weight(1f),
        )
    }
    }
}

@Composable
private fun QqDailyCard(
    playlist: MusicPlaylist,
    date: String? = null,
    loading: Boolean,
    message: String?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val artwork by rememberArtworkBitmap(playlist.artworkUrl)
    var visualArtwork by remember { mutableStateOf<Bitmap?>(null) }
    val effectiveArtwork = visualArtwork ?: artwork
    LaunchedEffect(playlist.id, effectiveArtwork) { transition?.updateArtwork(playlist.id, effectiveArtwork) }
    Surface(
        modifier = modifier.fillMaxHeight().bringIntoViewRequester(requester).clip(shape).clickable(
            enabled = transition?.busy != true && motion?.mounted != true,
            onClickLabel = "打开每日推荐",
        ) {
            if (transition == null || playlist.tracks.isEmpty()) onClick()
            else transition.open(playlist.id, effectiveArtwork, {
                // 与下方歌单一致：先把来源卡完整露出，再开始共享元素动画。
                requester.bringIntoView()
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            }, onClick)
        },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(Modifier.fillMaxSize()) {
            CrossfadingArtworkBitmapOrPlaceholder(
                artwork,
                playlist.artworkUrl?.isNotBlank() == true && artwork == null,
                playlist.artworkStart,
                playlist.artworkEnd,
                playlist.artworkMark,
                Modifier.fillMaxSize().motionAnchor(
                    motion,
                    playlist.id,
                    false,
                    corner = 24f,
                    markSize = 62f,
                    markX = 0f,
                    markY = 0f,
                ),
                62.sp,
                shape,
                Alignment.Center,
                onDisplayedArtworkChange = { visualArtwork = it },
            )
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = transition?.alphaFor(playlist.id) ?: 1f
                }.background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = .28f),
                            Color.Black.copy(alpha = .36f),
                            Color.Black.copy(alpha = .72f),
                        ),
                    ),
                ),
            )
            Column(
                Modifier.fillMaxSize().padding(16.dp).graphicsLayer {
                    alpha = transition?.alphaFor(playlist.id) ?: 1f
                },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = .9f), modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(dayOfMonth(date), color = Color(0xFF176C5B), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("每日推荐", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.35).sp)
                    Text(
                        when {
                            loading && playlist.tracks.isEmpty() -> "正在更新"
                            message != null && playlist.tracks.isEmpty() -> "轻触重试"
                            else -> "今日歌单 · ${playlist.count} 首"
                        },
                        color = Color.White.copy(alpha = .84f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun QqRadioCard(
    active: Boolean,
    playing: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable(
            onClickLabel = if (active) "打开猜你喜欢" else "播放猜你喜欢",
            onClick = onClick,
        ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(82.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color(0xFF28B887),
                                modifier = Modifier.size(50.dp),
                            )
                        }
                    }
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(42.dp), shadowElevation = 1.dp) {
                    Box(contentAlignment = Alignment.Center) {
                        QqRadioPlaybackIndicator(active, playing, loading)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("猜你喜欢", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.35).sp)
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF28B887),
                        modifier = Modifier.size(15.dp))
                }
                Text(if (active && playing) "正在播放 · 持续推荐" else if (active) "已暂停 · 持续推荐" else "为你连续推荐",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun QqPlaylistCard(
    playlist: MusicPlaylist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 152.dp,
    showSubtitle: Boolean = true,
    artworkVersion: Long? = null,
    sharedTransition: Boolean = true,
    prominentTitle: Boolean = false,
    compactText: Boolean = false,
    onLongClick: ((Rect) -> Unit)? = null,
    artworkMaxSide: Int = 768,
    sourceKey: String = playlist.id,
) {
    val shape = RoundedCornerShape(20.dp)
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val requestedArtwork by rememberArtworkBitmap(
        playlist.artworkUrl,
        artworkVersion?.takeIf { it > 0L },
        maxSide = artworkMaxSide,
    )
    // 直接观察加载状态，封面从磁盘或网络完成后无需依赖点击触发再次组合。
    val artwork = requestedArtwork ?: ArtworkRepository.peek(playlist.artworkUrl, artworkMaxSide)
    LaunchedEffect(sourceKey, artwork) { transition?.updateArtwork(sourceKey, artwork) }
    BoxWithConstraints(
        modifier.widthIn(max = width).aspectRatio(1f).bringIntoViewRequester(requester).clip(shape)
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }.combinedClickable(
            enabled = transition?.busy != true && motion?.mounted != true,
            onClickLabel = "打开${playlist.title}",
            onLongClickLabel = "管理歌单",
            hapticFeedbackEnabled = false,
            onLongClick = onLongClick?.let { action -> { action(cardBounds) } },
            onClick = {
            if (transition == null || !sharedTransition) onClick()
            else transition.open(sourceKey, artwork, {
                requester.bringIntoView()
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            }, onClick)
        }),
    ) {
        CrossfadingArtworkBitmapOrPlaceholder(
            artwork,
            playlist.artworkUrl?.isNotBlank() == true && artwork == null,
            playlist.artworkStart,
            playlist.artworkEnd,
            playlist.artworkMark,
            Modifier.fillMaxSize().motionAnchor(
                motion,
                sourceKey,
                false,
                corner = 20f,
                markSize = 48f,
                markX = 0f,
                markY = 0f,
            ),
            48.sp,
            shape,
            Alignment.Center,
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = transition?.alphaFor(sourceKey) ?: 1f
            }.background(Brush.verticalGradient(listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = .78f),
            ))),
        )
        val textWidth = constraints.maxWidth - with(androidx.compose.ui.platform.LocalDensity.current) {
            14.dp.roundToPx() + 58.dp.roundToPx()
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 58.dp, bottom = 13.dp)
                .graphicsLayer { alpha = transition?.alphaFor(sourceKey) ?: 1f },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (prominentTitle) {
                Text(playlist.title, color = Color.White,
                    fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else QqMusicFeedText(playlist.title, textWidth, color = Color.White, emphasis = true, preferredLines = 3,
                compact = compactText)
            if (showSubtitle && playlist.subtitle.isNotBlank()) {
                QqMusicFeedText(
                    playlist.subtitle,
                    textWidth,
                    color = Color.White.copy(alpha = .9f),
                    preferredLines = 1,
                    maxLines = 1,
                    compact = compactText,
                )
            }
        }
        Surface(
            onClick = onPlay,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(42.dp).graphicsLayer {
                alpha = transition?.alphaFor(sourceKey) ?: 1f
            },
            shape = CircleShape,
            color = Color.White.copy(alpha = .94f),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "播放歌单", tint = Color(0xFF202124),
                    modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
internal fun QqInlineMessage(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Text(message, Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private fun dayOfMonth(date: String? = null): String {
    if (!date.isNullOrBlank()) {
        val day = date.substringAfterLast('-')
        if (day.length == 2 && day.all { it.isDigit() }) return day
    }
    return java.text.SimpleDateFormat("dd", java.util.Locale.CHINA).format(java.util.Date())
}
