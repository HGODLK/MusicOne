package com.musicone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import com.musicone.source.sdk.SourceEntityRef
import com.musicone.source.sdk.artwork
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.sourceReference
import kotlinx.coroutines.CancellationException

internal class MenuArtistSlot(val key: String, val mid: String, val mark: String, val avatar: ArtistAvatarPresentation) {
    var bounds by mutableStateOf(Rect.Zero)
    var visibleBounds by mutableStateOf(Rect.Zero)
    var visible by mutableStateOf(true)
    var contentAlpha by mutableStateOf<State<Float>?>(null)
}
internal class MenuArtistArtworkState(scope: kotlinx.coroutines.CoroutineScope) {
    val avatars = MenuArtistLookupCache<ArtistAvatarPresentation>(scope)
    val slots = mutableStateListOf<MenuArtistSlot>()
    var rotation = 0
}
internal val LocalMenuArtistArtwork = staticCompositionLocalOf<MenuArtistArtworkState?> { null }
internal val LocalMenuArtistContentAlpha = staticCompositionLocalOf<State<Float>?> { null }

/** 菜单内部只放占位，真实头像在菜单快照树外绘制。 */
@Composable
internal fun MenuArtistArtwork(mid: String, key: String, mark: String, visible: Boolean = true) {
    val state = LocalMenuArtistArtwork.current ?: return
    // 只有实际进入菜单的歌手行才准备头像，不遍历歌单曲目。
    val track = LocalMainSourcePlaylistSongMenu.current?.track
    val source = track?.source
    val reference = track?.artistRefs?.firstOrNull { it.id == mid }?.sourceReference()
        ?: SourceEntityRef(mid, mark, "artist")
    val context = LocalContext.current
    val cacheKey = "${source?.id}:$mid"
    var avatar by remember(state, cacheKey) { mutableStateOf(state.avatars.peek(cacheKey)) }
    LaunchedEffect(state, cacheKey, reference) {
        try {
            avatar = state.avatars.load(cacheKey) {
                val entities = source?.let { SourcePackageController.get(context).running(it.id)?.entry?.entities }
                    ?: error("音源尚未准备歌手资料")
                val artwork = entities.artwork(reference).url
                ArtistAvatarPresentation(artwork)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 失败不写缓存，下一次打开允许重试。 */ }
    }
    val prepared = avatar
    if (prepared == null) {
        Spacer(Modifier.size(32.dp))
        return
    }
    PrepareArtistAvatar(prepared)
    val slot = remember(key, mid, mark, avatar) { MenuArtistSlot(key, mid, mark, prepared) }
    val contentAlpha = LocalMenuArtistContentAlpha.current
    DisposableEffect(state, slot) {
        state.slots += slot
        onDispose { state.slots.remove(slot) }
    }
    SideEffect { slot.visible = visible; slot.contentAlpha = contentAlpha }
    Spacer(Modifier.size(32.dp).entityArtworkAnchor(key, 16f, markSize = 14f)
        .onGloballyPositioned {
            // 使用完整布局矩形，避免搜索页裁剪边界变化挤压外置头像。
            slot.bounds = it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false)
            // 另存菜单尺寸动画与滚动视口的裁剪边界，外置层也必须遵守同一显示窗口。
            slot.visibleBounds = it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = true)
        })
}

/** 不参与菜单录制；源头像与飞行头像只在同一帧交接一次。 */
@Composable
internal fun MenuArtistArtworkOverlay(state: MenuArtistArtworkState, host: Rect, expanded: Boolean, rotation: Int = 0) {
    SideEffect { state.rotation = rotation }
    val navigation = LocalEntityNavigation.current
    val density = LocalDensity.current
    state.slots.toList().forEach { slot -> key(slot) {
        val opacity by animateFloatAsState(
            if (expanded) 1f else 0f,
            musicMotion(240),
            label = "菜单头像整体显隐",
        )
        val bounds = slot.bounds
        if (!bounds.isEmpty) ArtistAvatar(slot.avatar, slot.mark,
            Modifier.offset { IntOffset((bounds.left - host.left).roundToInt(), (bounds.top - host.top).roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .drawWithContent {
                    val clip = menuArtistLocalClip(bounds, slot.visibleBounds)
                    if (!clip.isEmpty) clipRect(clip.left, clip.top, clip.right, clip.bottom) { this@drawWithContent.drawContent() }
                }
                .graphicsLayer {
                    rotationZ = rotation.toFloat()
                    val frame = navigation?.pages?.lastOrNull()
                    // 详情转场由保留层接管整个来源菜单，原层不能留下第二份静止头像。
                    val retainedByPage = frame?.retainsMenuArtwork(state) == true
                    alpha = if (retainedByPage || (frame?.sourceKey == slot.key &&
                        frame.motion.moving && frame.motion.hasSharedCover)) 0f else opacity * (slot.contentAlpha?.value ?: 1f)
                }, 14.sp)
    } }
}

/** 专辑展开表面会覆盖来源菜单，此层与菜单快照同步退场，避免头像提前消失。 */
@Composable
internal fun RetainedMenuArtistArtworkOverlay(state: MenuArtistArtworkState, frame: EntityFrame) {
    val density = LocalDensity.current
    frame.menuArtwork.forEach { slot -> key(slot.key) {
        val bounds = slot.bounds
        if (slot.visible && !bounds.isEmpty) ArtistAvatar(slot.avatar, slot.mark,
            Modifier.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .graphicsLayer {
                    rotationZ = slot.rotation.toFloat()
                    // 起点固定，宿主位置和进度在绘制阶段共同读取，避免组合阶段旧 host 造成一帧跳动。
                    val progress = frame.motion.value
                    val excluded = frame.sourceKey.takeIf { frame.motion.hasSharedCover }
                    val offset = menuArtistHandoffOffset(bounds, frame.motion.hostBounds,
                        with(density) { -20.dp.toPx() * progress })
                    translationX = offset.x.toFloat()
                    translationY = offset.y.toFloat()
                    alpha = if (frame.retainsMenuArtwork(state) &&
                        retainMenuArtistSlot(slot.key, slot.visible, excluded)) retainedMenuArtworkAlpha(progress) else 0f
                },
            14.sp,
        )
    } }
}

internal fun retainedMenuArtworkAlpha(progress: Float): Float =
    (1f - progress.coerceIn(0f, 1f) / .45f).coerceIn(0f, 1f)

/** 只排除正在共享转场的头像，其他可见歌手随菜单快照保留。 */
internal fun retainMenuArtistSlot(key: String, visible: Boolean, excludedKey: String?): Boolean =
    visible && key != excludedKey
