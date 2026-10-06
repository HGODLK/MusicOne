package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
internal fun PlaylistMotionHost(
    motion: PageMotion,
    playlist: MusicPlaylist,
    backgroundVisual: NeteaseProfileVisual? = null,
    content: @Composable () -> Unit,
) {
    val pull = androidx.compose.runtime.remember(playlist.id) { PlaylistPullState() }
    val revealShape = androidx.compose.runtime.remember(motion, pull) { PlaylistRevealShape(motion, pull) }
    androidx.compose.runtime.LaunchedEffect(motion.phase) {
        if (motion.phase == MotionPhase.SHOWN) pull.active = false
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPlaylistPull provides pull) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { motion.updateHost(it.boundsInRoot()) }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            // 共享封面已有几何揭露，底色保持不透明，防止封面淡白边缘透出首页深色。
            alpha = if (motion.moving && motion.hasSharedCover && !pull.active) 1f else motion.value
            shape = revealShape
            clip = true
        }.background(if (backgroundVisual == null) PlaylistPageColor else androidx.compose.ui.graphics.Color.Transparent).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }) {
            backgroundVisual?.let { NeteaseProfileBackdrop(it, Modifier.fillMaxSize()) }
        }
        // 目标页在准备阶段也要完成绘制，供根叠加层的玻璃按钮预先采样；各内容自行处理可见度。
        Box(Modifier.fillMaxSize()) { content() }
        if (motion.phase == MotionPhase.PREPARING || motion.phase == MotionPhase.MOVING) {
            // 动画中的透明页面仍消费触摸，防止点击穿透到首页。
            Box(Modifier.fillMaxSize().pointerInput(motion) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
        }
    }
    }
}

internal class PlaylistRevealShape(private val motion: PageMotion, private val pull: PlaylistPullState) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val source = motion.sourceSnapshot[motion.coverKey]
        val target = motion.targetSnapshot[motion.coverKey]
        if (pull.active || !motion.moving || !motion.hasSharedCover || source == null || target == null) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        // 手势退出保持整页连续淡出，普通返回沿共享封面的落点收拢。
        val bounds = motionRect(source.bounds, motion.hostBounds, motion.value)
            .translate(-motion.hostBounds.left, -motion.hostBounds.top)
        val corner = CornerRadius(with(density) { (source.corner * (1f - motion.value)).dp.toPx() })
        return Outline.Rounded(RoundRect(bounds, corner, corner, corner, corner))
    }
}
