package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal fun Modifier.motionAnchor(
    motion: PageMotion?, key: String, target: Boolean,
    corner: Float = 0f, markSize: Float = 0f, markX: Float = 1f, markY: Float = -1f,
    bottomFade: Float = 0f,
    hideDuringMotion: Boolean = true,
    boundsScale: Float = 1f,
    contentScale: Float = 1f,
    textSizeSp: Float = 0f,
): Modifier = composed {
    if (motion == null) return@composed this
    val map = if (target) motion.targets else motion.sources
    val ownedAnchor = remember(motion, key, target) { arrayOfNulls<MotionAnchor>(1) }
    DisposableEffect(motion, key, target) {
        onDispose {
            // 交叉淡入期间旧布局卸载不能删除新布局的同名锚点。
            if (map[key] === ownedAnchor[0]) map.remove(key)
        }
    }
    this.onGloballyPositioned {
        val bounds = it.boundsInRoot()
        // 被列表裁掉的封面不作为飞行起终点。
        if (bounds.width >= it.size.width * boundsScale - 1f && bounds.height >= it.size.height * boundsScale - 1f && bounds.width > 1f && bounds.height > 1f) {
            val anchor = MotionAnchor(bounds, corner, markSize, markX, markY, bottomFade, contentScale, textSizeSp)
            map[key] = anchor
            ownedAnchor[0] = map[key]
        } else map.remove(key)
    }.graphicsLayer {
        alpha = if (hideDuringMotion && motion.moving && motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null) 0f else 1f
    }
}

internal fun Modifier.playlistDetailReveal(): Modifier = composed {
    val motion = LocalPlaylistMotion.current
    val entrance = LocalPlaylistContentEntrance.current
    val density = LocalDensity.current
    graphicsLayer {
        val reveal = playlistDetailProgress(motion?.value ?: 1f, entrance())
        alpha = reveal
        translationY = with(density) { 24.dp.toPx() } * (1f - reveal)
    }
}
