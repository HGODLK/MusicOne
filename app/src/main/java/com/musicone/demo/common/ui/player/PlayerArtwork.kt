package com.musicone.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun PlayerArtwork(track: MusicTrack, modifier: Modifier, markSize: TextUnit, shape: Shape) {
    // 转场锚点放在外层，旧封面淡出时不会注销新封面的锚点。
    val frame by rememberPlayerArtwork(track)
    ReadyArtworkCrossfade(frame, 420, modifier, modulateAlpha = true) { artwork ->
        val bitmap = artwork.bitmap
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
        } else {
            val identity = artwork.identity
            Artwork(identity.start, identity.end, identity.mark,
                Modifier.fillMaxSize(), markSize, Alignment.TopEnd, shape)
        }
    }
}
