package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp

/** 页面只登记操作，根层保留同一组玻璃按钮，跨页时不重复飞入。 */
@Composable
internal fun UnifiedPlaylistFloatingTools(
    state: PlaylistFloatingToolsOverlayState,
    motion: PageMotion,
    entities: EntityNavigation,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val top = entities.pages.lastOrNull()
    val selected = top?.floatingTools ?: state
    val underneath = entities.pages.dropLast(1).lastOrNull()?.floatingTools
        ?: state.takeIf { motion.wantsOpen }
    val destination = if (top != null && !top.motion.wantsOpen && top.motion.mounted) {
        underneath
    } else if (top != null && selected.search == null) {
        underneath
    } else selected.takeIf { top?.motion?.wantsOpen ?: motion.wantsOpen }
    val visible = destination?.visible == true && destination.search != null
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(if (visible) 1f else 0f, musicMotion(if (visible) 360 else 280))
    }
    var retained by remember { mutableStateOf<PlaylistFloatingToolsOverlayState?>(null) }
    SideEffect { if (selected.search != null) retained = selected }
    val current = selected.takeIf { it.search != null } ?: retained ?: return
    val search = current.search ?: return
    if (!visible && progress.value == 0f) return
    val pageBackdrop = entities.currentPageBackdrop(backdropLayer, backdropBounds)
    PlaylistFloatingTools(search, current.canLocate, current::locate, pageBackdrop.layer, pageBackdrop.bounds,
        bottomInset, { progress.value }, modifier)
}
