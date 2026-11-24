package com.musicone

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt


import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun FeaturePlaylistCard(
    playlist: MusicPlaylist,
    modifier: Modifier = Modifier,
    desiredHeight: androidx.compose.ui.unit.Dp = 382.dp,
    compact: Boolean = false,
    onPlay: () -> Unit,
    onClick: () -> Unit,
) {
    val corner = if (compact) 20.dp else 22.dp
    val shape = RoundedCornerShape(corner)
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val viewport = LocalPlaylistCardViewport.current
    val cardHeight = minOf(desiredHeight, viewport.height.takeIf { it > 0.dp } ?: desiredHeight)
    val artwork by rememberArtworkBitmap(playlist.artworkUrl)
    val useDarkInk = remember(artwork, playlist.artworkEnd) {
        artwork?.recommendationCardUsesDarkInk()
            ?: (Color(playlist.artworkEnd).luminance() >= .58f)
    }
    val foreground = if (useDarkInk) Color(0xFF101318) else Color.White
    val protection = if (useDarkInk) Color.White else Color.Black
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(shape)
            .clickable(enabled = transition?.busy != true && motion?.mounted != true) {
                // 被横向列表或底栏挡住一部分也直接起飞，飞行按可见块裁剪，不再先滚动露出。
                if (transition == null) onClick() else transition.open(playlist.id, artwork, {}, onClick)
            },
    ) {
        PlaylistSyncedArtwork(
            artwork,
            playlist.artworkUrl?.isNotBlank() == true && artwork == null,
            playlist.artworkStart, playlist.artworkEnd,
            playlist.artworkMark,
            Modifier.fillMaxSize().motionAnchor(motion, playlist.id, false, partial = true, corner = corner.value, markSize = 120f, markX = 0f, markY = 0f),
            120.sp, shape, Alignment.Center,
            sourceKey = playlist.id, imageUrl = playlist.artworkUrl,
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = transition?.alphaFor(playlist.id) ?: 1f }
            .background(Brush.verticalGradient(listOf(Color.Transparent, protection.copy(alpha = .68f)))))
        Row(
            Modifier.fillMaxSize().padding(if (compact) 16.dp else 17.dp).graphicsLayer {
                alpha = transition?.alphaFor(playlist.id) ?: 1f
            },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                musicOneUiAnnotatedString(playlist.title),
                color = foreground,
                fontSize = (if (compact) 28 else 30).sp,
                lineHeight = (if (compact) 31 else 33).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.8).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    shadow = Shadow(
                        color = protection.copy(alpha = .72f),
                        offset = Offset(0f, 1f),
                        blurRadius = 7f,
                    ),
                ),
            )
            Spacer(Modifier.size(12.dp))
            Surface(
                onClick = { transition?.preparePlayback(playlist.id); onPlay() },
                shape = CircleShape,
                color = foreground,
                modifier = Modifier.size(if (compact) 40.dp else 42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "播放歌单",
                        tint = if (useDarkInk) Color.White else Color(0xFF1C2028),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

/** 只采样标题所在的下半区，避免天空等顶部亮色误导文字颜色。 */
internal fun Bitmap.recommendationCardUsesDarkInk(): Boolean {
    if (width <= 0 || height <= 0) return false
    val xs = floatArrayOf(.12f, .31f, .5f, .69f, .88f)
    val ys = floatArrayOf(.68f, .8f, .9f)
    var total = 0.0
    var count = 0
    ys.forEach { y ->
        xs.forEach { x ->
            total += Color(
                getPixel(
                    (x * (width - 1)).roundToInt().coerceIn(0, width - 1),
                    (y * (height - 1)).roundToInt().coerceIn(0, height - 1),
                ),
            ).luminance()
            count++
        }
    }
    return total / count >= .56
}
