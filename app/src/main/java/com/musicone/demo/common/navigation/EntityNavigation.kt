package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.ImageBitmap

internal sealed interface EntityTarget {
    data class Artist(val singer: QqSearchSinger) : EntityTarget
    data class Album(val playlist: MusicPlaylist) : EntityTarget
}

internal class EntityFrame(val key: Long, val target: EntityTarget, var sourceMenu: QqPlaylistSongMenuState?, scope: CoroutineScope) {
    val floatingTools = PlaylistFloatingToolsOverlayState()
    var statusBarColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    var origin = sourceMenu?.surfaceBounds ?: Rect.Zero
    val motion = PageMotion(scope, false, 420, 320).apply { waitForTarget = false }
    val progress get() = motion.progress
    var menu: QqPlaylistSongMenuState? = null
    var backAction: (() -> Unit)? = null
    var menuImage by mutableStateOf<ImageBitmap?>(null)
    var sourceKey: String? = null
    var pageLayer by mutableStateOf<androidx.compose.ui.graphics.layer.GraphicsLayer?>(null)
    var sourceBackdrop: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    var sourceBackdropBounds = Rect.Zero
    var artistAvatar: ArtistAvatarPresentation? = null
}

/** 菜单是页面的返回状态，不单独占层；淘汰时切断更早的返回引用。 */
internal class EntityNavigation(private val scope: CoroutineScope) {
    val pages = mutableStateListOf<EntityFrame>()
    private var preparing by mutableStateOf(false)
    val moving get() = preparing || pages.any { it.motion.moving || it.motion.phase == MotionPhase.PREPARING }
    val artworkSources = mutableStateMapOf<String, MotionAnchor>()
    private var preparation: Job? = null
    private var serial = 0L
    var rootMenu by mutableStateOf<QqPlaylistSongMenuState?>(null)
    fun open(target: EntityTarget, sourceMenu: QqPlaylistSongMenuState? = null, sourceKey: String? = null) {
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
                // 菜单真实头像位于录制树外，快照只包含玻璃、文字与占位。
                frame.menuImage = sourceMenu?.surfaceLayer?.let {
                    try { it.toImageBitmap() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
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
            // 菜单仍在时沿用进入时的来源，临时裁剪或重测不能丢失收起路径。
            frame.motion.sources.clear()
            if (frame.sourceKey != null) frame.origin = Rect.Zero
        }
        frame.motion.request(false)
    }
    fun clear() { preparation?.cancel(); preparing = false; pages.forEach { it.motion.onHidden = {}; it.motion.request(false) }; pages.clear(); rootMenu = null }
}

internal val LocalEntityNavigation = staticCompositionLocalOf<EntityNavigation?> { null }
internal val LocalEntityFrame = staticCompositionLocalOf<EntityFrame?> { null }
internal val LocalEntityActive = staticCompositionLocalOf { true }

@Composable
internal fun rememberEntityNavigation(): EntityNavigation {
    val scope = rememberCoroutineScope()
    return remember(scope) { EntityNavigation(scope) }
}
