package com.musicone.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

internal class MenuArtistSlot(val key: String, val mid: String, val mark: String, val avatar: ArtistAvatarPresentation) {
    var bounds by mutableStateOf(Rect.Zero)
    var visible by mutableStateOf(true)
}
internal class MenuArtistArtworkState {
    val slots = mutableStateListOf<MenuArtistSlot>()
}
internal val LocalMenuArtistArtwork = staticCompositionLocalOf<MenuArtistArtworkState?> { null }

/** 菜单内部只放占位，真实头像在菜单快照树外绘制。 */
@Composable
internal fun MenuArtistArtwork(mid: String, key: String, mark: String, visible: Boolean = true) {
    val state = LocalMenuArtistArtwork.current ?: return
    // 只有实际进入菜单的歌手行才准备头像，不遍历歌单曲目。
    val avatar = rememberArtistAvatar("https://y.gtimg.cn/music/photo_new/T001R500x500M000${mid}.jpg")
    val slot = remember(key, mid, mark, avatar) { MenuArtistSlot(key, mid, mark, avatar) }
    DisposableEffect(state, slot) {
        state.slots += slot
        onDispose { state.slots.remove(slot) }
    }
    SideEffect { slot.visible = visible }
    Spacer(Modifier.size(32.dp).entityArtworkAnchor(key, 16f, markSize = 14f)
        .onGloballyPositioned { slot.bounds = it.boundsInRoot() })
}

/** 不参与菜单录制；源头像与飞行头像只在同一帧交接一次。 */
@Composable
internal fun MenuArtistArtworkOverlay(state: MenuArtistArtworkState, host: Rect, expanded: Boolean) {
    val navigation = LocalEntityNavigation.current
    val density = LocalDensity.current
    state.slots.toList().forEach { slot -> key(slot) {
        val opacity by animateFloatAsState(
            if (expanded && slot.visible) 1f else 0f,
            musicMotion(if (slot.visible) 240 else 160),
            label = "菜单头像内容显隐",
        )
        val bounds = slot.bounds
        if (!bounds.isEmpty) ArtistAvatar(slot.avatar, slot.mark,
            Modifier.offset { IntOffset((bounds.left - host.left).roundToInt(), (bounds.top - host.top).roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .graphicsLayer {
                    val frame = navigation?.pages?.lastOrNull()
                    alpha = if (frame?.sourceKey == slot.key && frame.motion.moving && frame.motion.hasSharedCover) 0f else opacity
                }, 14.sp)
    } }
}

/** 专辑展开表面会覆盖来源菜单，此层与菜单快照同步退场，避免头像提前消失。 */
@Composable
internal fun RetainedMenuArtistArtworkOverlay(state: MenuArtistArtworkState, host: Rect, progress: Float, excludedKey: String? = null) {
    if (progress >= .45f) return
    val density = LocalDensity.current
    val alpha = retainedMenuArtworkAlpha(progress)
    val translationY = with(density) { -20.dp.toPx() * progress }
    state.slots.toList().forEach { slot -> key(slot) {
        val bounds = slot.bounds
        if (retainMenuArtistSlot(slot.key, slot.visible, excludedKey) && !bounds.isEmpty) ArtistAvatar(slot.avatar, slot.mark,
            Modifier.offset {
                IntOffset(
                    (bounds.left - host.left).roundToInt(),
                    (bounds.top - host.top + translationY).roundToInt(),
                )
            }.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .graphicsLayer { this.alpha = alpha },
            14.sp,
        )
    } }
}

internal fun retainedMenuArtworkAlpha(progress: Float): Float =
    (1f - progress.coerceIn(0f, 1f) / .45f).coerceIn(0f, 1f)

/** 只排除正在共享转场的头像，其他可见歌手随菜单快照保留。 */
internal fun retainMenuArtistSlot(key: String, visible: Boolean, excludedKey: String?): Boolean =
    visible && key != excludedKey
