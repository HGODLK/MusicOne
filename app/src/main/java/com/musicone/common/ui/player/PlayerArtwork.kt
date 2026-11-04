package com.musicone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun PlayerArtwork(track: MusicTrack, motion: PageMotion, modifier: Modifier, markSize: TextUnit, shape: Shape,
    shapeProvider: (() -> Shape)? = null) {
    // 转场锚点放在外层，旧封面淡出时不会注销新封面的锚点。
    val frame = rememberPlayerOpeningArtwork(track, motion)
    ReadyArtworkCrossfade(frame, 420, modifier, modulateAlpha = true,
        adoptPreparedFrame = motion.wantsOpen &&
            (motion.phase == MotionPhase.PREPARING || motion.phase == MotionPhase.MOVING) &&
            PlayerOpeningArtworkHandoff.frameFor(track) != null) { artwork ->
        val bitmap = artwork.bitmap
        val liveClip = if (shapeProvider == null) Modifier else Modifier.graphicsLayer {
            this.shape = shapeProvider()
            clip = true
        }
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().then(
                if (shapeProvider == null) Modifier.clip(shape) else liveClip), contentScale = ContentScale.Crop)
        } else {
            val identity = artwork.identity
            Artwork(identity.start, identity.end, identity.mark,
                // 动态圆角由外层图层统一裁剪，内部矩形不再叠加原静态圆角。
                Modifier.fillMaxSize().then(liveClip), markSize, Alignment.TopEnd,
                if (shapeProvider == null) shape else RectangleShape)
        }
    }
}
