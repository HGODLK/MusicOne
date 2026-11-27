package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp

internal sealed interface EntityTarget {
    data class Artist(val singer: MainSourceArtist) : EntityTarget
    data class Album(val playlist: MusicPlaylist) : EntityTarget
}

internal class EntityFrame(val key: Long, val target: EntityTarget, var sourceMenu: MainSourcePlaylistSongMenuState?, scope: CoroutineScope) {
    val floatingTools = PlaylistFloatingToolsOverlayState()
    var statusBarColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    var origin = sourceMenu?.surfaceBounds ?: Rect.Zero
    val menuArtwork = sourceMenu?.artistArtworkState?.captureHandoff().orEmpty()
    val motion = PageMotion(scope, false, 420, 320).apply { waitForTarget = false }
    val progress get() = motion.progress
    var menu: MainSourcePlaylistSongMenuState? = null
    var backAction: (() -> Unit)? = null
    var menuImage by mutableStateOf<ImageBitmap?>(null)
    var sourceKey: String? = null
    var pageLayer by mutableStateOf<androidx.compose.ui.graphics.layer.GraphicsLayer?>(null)
    var sourceBackdrop: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var sourceBackdropBounds = Rect.Zero
    var artistAvatar: ArtistAvatarPresentation? = null
}

/** 来源菜单跟着页面帧走，不单独占页面栈；栈超限时顺手清掉被挤掉那一帧的返回引用。 */
internal class EntityNavigation(private val scope: CoroutineScope) {
    val pages = mutableStateListOf<EntityFrame>()
    private var preparing by mutableStateOf(false)
    val moving get() = preparing || pages.any { it.motion.moving || it.motion.phase == MotionPhase.PREPARING }
    val artworkSources = mutableStateMapOf<String, MotionAnchor>()
    private var preparation: Job? = null
    private var serial = 0L
    var rootMenu by mutableStateOf<MainSourcePlaylistSongMenuState?>(null)
    fun open(target: EntityTarget, sourceMenu: MainSourcePlaylistSongMenuState? = null, sourceKey: String? = null) {
        if (moving) return
        val frame = EntityFrame(++serial, target, sourceMenu, scope)
        frame.sourceBackdrop = sourceMenu?.backdropLayer ?: pages.lastOrNull()?.pageLayer
        frame.sourceBackdropBounds = sourceMenu?.backdropBounds ?: pages.lastOrNull()?.motion?.hostBounds ?: Rect.Zero
        val key = when (target) { is EntityTarget.Artist -> target.singer.id; is EntityTarget.Album -> target.playlist.id }
        frame.motion.coverKey = key
        frame.sourceKey = sourceKey
        if (target is EntityTarget.Artist) {
            frame.artistAvatar = sourceMenu?.artistArtworkState?.slots
                ?.firstOrNull { it.key == sourceKey }?.avatar ?: ArtistAvatarPresentation(target.singer.artwork)
        }
        sourceKey?.let(artworkSources::get)?.let { source ->
            frame.motion.sources[key] = source
            frame.motion.waitForTarget = true
            if (sourceMenu == null) frame.origin = source.bounds
        }
        frame.motion.onHidden = { pages.remove(frame) }
        pages += frame
        if (pages.size > 8) {
            pages.removeAt(0).sourceMenu?.dismiss()
            pages.first().apply {
                this.sourceMenu = null; origin = Rect.Zero; this.sourceKey = null; motion.sources.clear()
                sourceBackdrop = null; sourceBackdropBounds = Rect.Zero
            }
        }
        preparing = true
        preparation = scope.launch {
            try {
                // 菜单快照与头像终点的布局等待互不依赖，并行进行：页面加入 pages 后已在组合，不必等截图结束再开始等布局。
                val image = sourceMenu?.surfaceLayer?.let { layer ->
                    async {
                        // 菜单真实头像位于录制树外，快照只包含玻璃、文字与占位。
                        try { layer.toImageBitmap() }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                }
                // 终点在这里已经稳定时转场不再从头等一遍；没等到就交回转场原有的等待和淡入回退。
                if (image != null && frame.motion.waitForTarget && awaitEntityTargetAnchor(frame.motion)) {
                    frame.motion.waitForTarget = false
                }
                frame.menuImage = image?.await()
                frame.motion.request(true)
            } finally { preparing = false }
        }
    }

    fun back() {
        val frame = pages.lastOrNull() ?: return
        if (frame.menu?.expanded == true) { frame.menu?.back(); return }
        if (preparing) {
            preparation?.cancel(); preparing = false; pages.remove(frame); return
        }
        val source = frame.sourceKey?.let(artworkSources::get)
        if (source != null) frame.motion.sources[frame.motion.coverKey] = source
        else if (frame.sourceMenu?.expanded != true) {
            // 菜单还开着时保留进入时的起点坐标，避免重测导致返回动画找不到落点。
            frame.motion.sources.clear()
            if (frame.sourceKey != null) frame.origin = Rect.Zero
        }
        frame.motion.request(false)
    }
    fun clear() { preparation?.cancel(); preparing = false; pages.forEach { it.motion.onHidden = {}; it.motion.request(false) }; pages.clear(); rootMenu = null }
}

/** 与转场自身的布局等待同一口径：终点锚点连续两帧不变视为稳定，最多等 160ms。 */
private suspend fun awaitEntityTargetAnchor(motion: PageMotion): Boolean = withTimeoutOrNull(160L) {
    var last: Rect? = null
    var matches = 0
    while (matches < 2) {
        withFrameNanos { }
        val bounds = motion.targets[motion.coverKey]?.bounds
        matches = if (bounds != null && bounds == last) matches + 1 else 0
        last = bounds
    }
    true
} == true

/** 歌手、专辑页展开时来源菜单原地淡出、返回时原地淡入，不再上下漂移；菜单快照、返回菜单和保留头像共用这个位移。 */
internal val EntitySourceDrift = 0.dp

internal val LocalEntityNavigation = staticCompositionLocalOf<EntityNavigation?> { null }
internal val LocalEntityFrame = staticCompositionLocalOf<EntityFrame?> { null }
internal val LocalEntityActive = staticCompositionLocalOf { true }

@Composable
internal fun rememberEntityNavigation(): EntityNavigation {
    val scope = rememberCoroutineScope()
    return remember(scope) { EntityNavigation(scope) }
}
