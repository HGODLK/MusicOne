package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal fun Modifier.entityArtworkAnchor(key: String, corner: Float, markSize: Float = 24f): Modifier = composed {
    val navigation = LocalEntityNavigation.current ?: return@composed this
    val owned = remember(key) { arrayOfNulls<MotionAnchor>(1) }
    DisposableEffect(key) { onDispose {
        if (navigation.artworkSources[key] === owned[0]) navigation.artworkSources.remove(key)
    } }
    onGloballyPositioned {
        val bounds = it.boundsInRoot()
        if (bounds.width >= it.size.width - 1 && bounds.height >= it.size.height - 1 && !bounds.isEmpty) {
            val anchor = MotionAnchor(bounds, corner, markSize, 0f, 0f)
            owned[0] = anchor; navigation.artworkSources[key] = anchor
        } else navigation.artworkSources.remove(key)
    }.drawWithContent {
        val frame = navigation.pages.lastOrNull()
        val flying = frame?.sourceKey == key && frame.motion.moving && frame.motion.hasSharedCover
        if (!flying) drawContent()
    }
}
