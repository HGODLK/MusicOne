package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 独立 ComposeView 的局部根坐标转换为宿主共享动效的根坐标。 */
internal val LocalMotionRootOffset = staticCompositionLocalOf { Offset.Zero }
internal val LocalMotionRootOffsetReader = staticCompositionLocalOf<(() -> Offset)?> { null }

internal fun Modifier.motionAnchor(
    motion: PageMotion?, key: String, target: Boolean,
    corner: Float = 0f, markSize: Float = 0f, markX: Float = 1f, markY: Float = -1f,
    bottomFade: Float = 0f,
    hideDuringMotion: Boolean = true,
    boundsScale: Float = 1f,
    contentScale: Float = 1f,
    textSizeSp: Float = 0f,
    // 歌单卡片等来源：被列表裁掉一部分也照常作为起终点，按完整位置飞行、不做裁剪；
    // 压在顶栏、底栏下的部分本就在玻璃下方，由玻璃采样自然模糊，裁掉反而截断并丢失采样。
    partial: Boolean = false,
): Modifier = composed {
    if (motion == null) return@composed this
    val map = if (target) motion.targets else motion.sources
    val rootOffset = LocalMotionRootOffsetReader.current?.invoke() ?: LocalMotionRootOffset.current
    val sourceMotionEnabled = LocalSourcePlayerBarMotionEnabled.current
    val sourceHandoff = LocalSourcePlayerBarHandoff.current
    val ownedAnchor = remember(motion, key, target) { arrayOfNulls<MotionAnchor>(1) }
    DisposableEffect(motion, key, target) {
        onDispose {
            // 交叉淡入期间旧布局卸载不能删除新布局的同名锚点。
            if (map[key] === ownedAnchor[0]) map.remove(key)
        }
    }
    this.onGloballyPositioned {
        if (partial) {
            val full = it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false)
            val visible = it.boundsInRoot()
            val anchor = if (visible.width > 1f && visible.height > 1f) MotionAnchor(full.translate(rootOffset), corner, markSize,
                markX, markY, bottomFade, contentScale, textSizeSp,
                visible = visible.takeIf { v -> v.width < full.width - 1f || v.height < full.height - 1f }?.translate(rootOffset))
                else null
            if (anchor != null) { map[key] = anchor; ownedAnchor[0] = anchor } else map.remove(key)
            return@onGloballyPositioned
        }
        val bounds = it.boundsInRoot().translate(rootOffset)
        // 被列表裁掉的封面不作为飞行起终点（允许部分露出的来源走上面的分支）。
        if (bounds.width >= it.size.width * boundsScale - 1f && bounds.height >= it.size.height * boundsScale - 1f && bounds.width > 1f && bounds.height > 1f) {
            val anchor = MotionAnchor(bounds, corner, markSize, markX, markY, bottomFade, contentScale, textSizeSp)
            map[key] = anchor
            ownedAnchor[0] = map[key]
        } else map.remove(key)
    }.graphicsLayer {
        alpha = if ((target || sourceMotionEnabled) && hideDuringMotion && motion.moving && motion.sourceSnapshot[key] != null && motion.targetSnapshot[key] != null) 0f
            else if (!target && hideDuringMotion && sourceHandoff?.hides(key, motion) == true) 0f else 1f
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
