package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

/** 首次淡入从详情页可见后开始，缓存命中也不在隐藏准备期提前播完。 */
@Composable
internal fun ArtistBackgroundArtwork(url: String, modifier: Modifier = Modifier) {
    val bitmap by rememberArtworkBitmap(url)
    val motion = LocalPlaylistMotion.current
    val alpha = remember(url) { Animatable(0f) }
    var revealed by remember(url) { mutableStateOf(false) }
    val shown = motion == null || motion.phase == MotionPhase.SHOWN
    LaunchedEffect(url, bitmap, shown) {
        if (bitmap != null && shown && !revealed) {
            alpha.animateTo(1f, artistArtworkRevealMotion())
            revealed = true
        }
    }
    Box(modifier) {
        bitmap?.let { image ->
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
                contentScale = ContentScale.Crop)
        }
    }
}
