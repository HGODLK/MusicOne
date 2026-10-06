package com.musicone.demo
import androidx.compose.foundation.clickable

import android.graphics.Bitmap

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull

@Composable
internal fun RemoteArtwork(
    imageUrl: String?,
    start: Long,
    end: Long,
    mark: String,
    modifier: Modifier,
    markSize: TextUnit,
    shape: androidx.compose.ui.graphics.Shape,
    markAlignment: Alignment = Alignment.TopEnd,
    maxSide: Int = 768,
) {
    val bitmap by rememberArtworkBitmap(imageUrl, maxSide = maxSide)
    ArtworkBitmapOrPlaceholder(bitmap, start, end, mark, modifier, markSize, shape, markAlignment)
}

@Composable
internal fun ArtworkBitmapOrPlaceholder(
    bitmap: Bitmap?,
    start: Long,
    end: Long,
    mark: String,
    modifier: Modifier,
    markSize: TextUnit,
    shape: androidx.compose.ui.graphics.Shape,
    markAlignment: Alignment = Alignment.TopEnd,
) {
    if (bitmap == null) {
        Artwork(start, end, mark, modifier, markSize, markAlignment, shape)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
internal fun CrossfadingArtworkBitmapOrPlaceholder(
    bitmap: Bitmap?,
    awaitingArtwork: Boolean,
    start: Long,
    end: Long,
    mark: String,
    modifier: Modifier,
    markSize: TextUnit,
    shape: androidx.compose.ui.graphics.Shape,
    markAlignment: Alignment = Alignment.TopEnd,
    fadeInitialArtwork: Boolean = false,
) {
    val candidate = if (awaitingArtwork) null else ArtworkCrossfadeFrame(bitmap, start, end, mark)
    val latest by rememberUpdatedState(candidate)
    var displayed by remember { mutableStateOf(candidate.takeUnless { fadeInitialArtwork }
        ?: ArtworkCrossfadeFrame(null, start, end, mark)) }
    var incoming by remember { mutableStateOf<ArtworkCrossfadeFrame?>(null) }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // 新远程封面准备好前保留旧图；新图只覆盖淡入，避免中途露出占位或页面底色。
        snapshotFlow { latest }.filterNotNull().conflate().collect { next ->
            if (!displayed.hasSameVisual(next)) {
                opacity.snapTo(0f)
                incoming = next
                opacity.animateTo(1f, musicMotion(360))
                displayed = next
                incoming = null
            } else if (next != displayed) {
                // 强制刷新得到相同像素时只替换引用，不制造一次肉眼可见的闪烁。
                displayed = next
            }
        }
    }
    Box(modifier) {
        ArtworkBitmapOrPlaceholder(
            displayed.bitmap,
            displayed.start,
            displayed.end,
            displayed.mark,
            Modifier.fillMaxSize(),
            markSize,
            shape,
            markAlignment,
        )
        incoming?.let { next ->
            ArtworkBitmapOrPlaceholder(
                next.bitmap,
                next.start,
                next.end,
                next.mark,
                Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
                markSize,
                shape,
                markAlignment,
            )
        }
    }
}

private data class ArtworkCrossfadeFrame(
    val bitmap: Bitmap?,
    val start: Long,
    val end: Long,
    val mark: String,
)

private fun ArtworkCrossfadeFrame.hasSameVisual(other: ArtworkCrossfadeFrame): Boolean {
    val current = bitmap
    val next = other.bitmap
    if (current != null || next != null) {
        if (current === next) return true
        if (current == null || next == null || current.width != next.width || current.height != next.height) return false
        return runCatching { current.sameAs(next) }.getOrDefault(false)
    }
    return start == other.start && end == other.end && mark == other.mark
}

@Composable
internal fun AccountAvatar(account: MusicAccount?, size: Dp, modifier: Modifier = Modifier) {
    val entry = LocalAccountEntry.current
    val avatarModifier = if (account == null && entry != null) modifier.then(
        Modifier.clickable(onClick = entry)) else modifier
    val shape = androidx.compose.foundation.shape.CircleShape
    val bitmap by rememberArtworkBitmap(account?.avatarUrl)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "账号头像",
            modifier = avatarModifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        androidx.compose.material3.Surface(
            modifier = avatarModifier.size(size),
            shape = shape,
            color = androidx.compose.ui.graphics.Color(0xFFDCE7FA),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = account?.nickname?.take(1)?.ifBlank { "我" } ?: "我",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
