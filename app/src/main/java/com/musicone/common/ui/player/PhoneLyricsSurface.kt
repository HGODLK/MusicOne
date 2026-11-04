package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

internal data class PhoneLyricsSlot(val viewport: PhoneLyricsViewport,
    val pivot: (Size) -> Offset, val hidden: () -> Boolean,
    val offset: () -> Offset = { Offset.Zero }, val velocity: () -> Offset = { Offset.Zero })

internal data class PhoneLyricsSurfaceRequest(val owner: Any, val slot: PhoneLyricsSlot,
    val lyrics: PlayerLyricsRequest, val spec: PlayerLayoutSpec, val moving: Boolean,
    val displayed: Boolean, val color: Color, val rotation: Int, val touchPadding: Dp)

/** 横竖布局只登记空槽，歌词及其裁剪、输入节点始终留在同一宿主。 */
internal class PhoneLyricsSurface {
    val gestures = PhoneLyricsSurfaceGestures()
    val turnFocus = PhoneLyricsTurnFocus()
    var request by mutableStateOf<PhoneLyricsSurfaceRequest?>(null)
    var bounds by mutableStateOf(Rect.Zero)
    var placedOwner by mutableStateOf<Any?>(null)
    var revision by mutableIntStateOf(0)
        private set
    private var pending: PhoneLyricsSurfaceRequest? = null
    private val placements = mutableMapOf<Any, LayoutCoordinates>()
    var coordinates: LayoutCoordinates? = null
        set(value) {
            field = value
            (pending ?: request)?.owner?.let { owner -> placements[owner]?.let { place(owner, it) } }
        }
    fun publish(value: PhoneLyricsSurfaceRequest) {
        // 新请求先等实际宿主，不能先换正文身份，再用上一份尺寸把整片歌词置为透明。
        pending = value
        placements[value.owner]?.let { place(value.owner, it) }
        if (pending === value) LyricDisplayDiagnostics.recovery("等待歌词宿主", value.owner)
    }
    fun place(owner: Any, value: LayoutCoordinates) {
        // 放置先于请求发布时也保留登记，不能漏掉唯一一次有效坐标而永久透明。
        placements[owner] = value
        val next = (pending ?: request)?.takeIf { it.owner === owner } ?: return
        val root = coordinates ?: return
        if (!root.isAttached || !value.isAttached) return
        val origin = root.localPositionOf(value, Offset.Zero)
        val nextBounds = Rect(origin, Size(value.size.width.toFloat(), value.size.height.toFloat()))
        if (nextBounds.isEmpty) return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            if (placedOwner !== owner || request?.rotation != next.rotation || bounds.size != nextBounds.size) revision++
            request = next
            bounds = nextBounds
            placedOwner = owner
        }
        if (pending === next) pending = null
    }
    fun detach(owner: Any) {
        if (placedOwner === owner) placedOwner = null
        placements.remove(owner)
        if (pending?.owner === owner) pending = null
    }
}

internal val LocalPhoneLyricsSurface = staticCompositionLocalOf<PhoneLyricsSurface?> { null }
internal val LocalPhoneLyricsSlot = staticCompositionLocalOf<PhoneLyricsSlot?> { null }
internal val LocalPhoneLyricsMountRevision = staticCompositionLocalOf { 0 }

@Composable
internal fun PhoneLyricsSlot(slot: PhoneLyricsSlot, content: @Composable () -> Unit) {
    if (LocalPhoneLyricsSurface.current != null) {
        CompositionLocalProvider(LocalPhoneLyricsSlot provides slot, content = content)
    } else {
        // 平板单栏仍在原舞台内绘制，方向由系统布局负责，不能套用手机旋转坐标。
        Box(Modifier.graphicsLayer {
            val offset = slot.offset()
            translationX = offset.x; translationY = offset.y
        }, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalPhoneLyricsSlot provides slot, content = content)
        }
    }
}

@Composable
internal fun PhoneLyricsSurfaceSlot(surface: PhoneLyricsSurface, slot: PhoneLyricsSlot,
    lyrics: PlayerLyricsRequest) {
    val owner = remember(surface) { Any() }
    val value = PhoneLyricsSurfaceRequest(owner, slot, lyrics, LocalPlayerLayoutSpec.current,
        LocalPlayerLyricsMoving.current, LocalPlayerLyricsDisplayed.current, LocalContentColor.current,
        LocalPhonePlayerRotation.current, LocalLandscapeLyricsTouchPadding.current)
    SideEffect { surface.publish(value) }
    DisposableEffect(surface, owner) { onDispose { surface.detach(owner) } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { surface.place(owner, it) })
}

@Composable
internal fun PhoneLyricsSurfaceHost(enabled: Boolean, pageMotion: PageMotion, content: @Composable BoxScope.() -> Unit) {
    val surface = remember { PhoneLyricsSurface() }
    val retained = LocalPlayerLyricsContent.current
    if (!enabled || retained == null) { Box(Modifier.fillMaxSize(), content = content); return }
    CompositionLocalProvider(LocalPhoneLyricsSurface provides surface) {
        Box(Modifier.fillMaxSize().phoneLyricsSurfaceGestures(surface.gestures).onGloballyPositioned {
            surface.coordinates = it
            surface.gestures.root = it
        }) {
            PhoneLyricsSurfaceContent(surface, retained, pageMotion)
            content()
        }
    }
}

@Composable
private fun PhoneLyricsSurfaceContent(surface: PhoneLyricsSurface, retained: PlayerLyricsContent, pageMotion: PageMotion) {
    val value = surface.request ?: return
    val attached = surface.placedOwner === value.owner
    val bounds = surface.bounds
    if (bounds.isEmpty) return
    val motion = LocalRetainedPhoneLyricsMotion.current
    val handoff = LocalPhonePlayerPresentation.current?.lyricsHandoff
    val turn = LocalPhonePlayerTurn.current
    val flight = LocalPhonePlayerFlightRotation.current
    val drawing = rememberPhoneLyricsDrawingWindow(surface, value, pageMotion)
    val pageHeld by remember(pageMotion) { derivedStateOf { phoneLyricsPageHeld(pageMotion.phase, pageMotion.value) } }
    CompositionLocalProvider(LocalPlayerLayoutSpec provides value.spec,
        LocalPhoneLyricsMountRevision provides surface.revision,
        LocalPlayerLyricsMoving provides value.moving,
        LocalPlayerLyricsDisplayed provides (attached && value.displayed),
        LocalLandscapeLyricsTouchPadding provides value.touchPadding,
        LocalPhoneLyricsDrawingWindow provides drawing,
        LocalPhoneLyricsTurnFocus provides surface.turnFocus,
        LocalContentColor provides value.color) {
        Layout(content = {
            PhoneTurnElement("lyrics", pivotForSize = value.slot.pivot, scaleContent = false,
                physicalRotation = value.rotation,
                hidden = value.slot.hidden, physicalOffset = value.slot.offset,
                independentPhysicalOffset = { phoneLyricsPageOffset(pageMotion, flight, value.rotation) },
                physicalVelocity = value.slot.velocity) {
                Box(Modifier.fillMaxSize().phoneLyricsTurnHandoff(handoff, turn,
                    value.lyrics.track.id, value.slot.viewport.top, value.slot.viewport.bottom)) {
                val request = value.lyrics
                retained.content(request.copy(
                    modifier = Modifier.phoneLyricsViewport(value.slot.viewport)
                        .then(request.modifier).graphicsLayer {
                            alpha = if (attached) (motion?.entranceAlpha?.value ?: 1f) *
                                (if (turn != null) handoff?.targetAlpha(turn, request.track.id) ?: 1f else 1f) else 0f
                        },
                    active = attached && request.active,
                    followPlayback = attached && request.followPlayback,
                    readingBounds = drawing.bounds(request.readingBounds),
                    boundaryWindow = request.boundaryWindow?.let { original ->
                        if (!drawing.extended) original else LyricBoundaryWindow(original.topBuffer,
                            original.offset, original.velocity, edges = { height, shift -> drawing.edges(height, original, shift) },
                            partialTop = original.partialTop)
                    },
                    onWindowLayout = request.onWindowLayout.takeUnless { drawing.extended },
                    heldTrackChange = request.heldTrackChange || pageHeld,
                    intermediateSeek = request.intermediateSeek || pageHeld,
                    lyricsGestureHeld = request.lyricsGestureHeld || pageHeld))
                }
            }
        }, modifier = Modifier.fillMaxSize()) { children, constraints ->
            val child = children.single().measure(Constraints.fixed(
                bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                child.place(bounds.left.roundToInt(), bounds.top.roundToInt())
            }
        }
    }
}
