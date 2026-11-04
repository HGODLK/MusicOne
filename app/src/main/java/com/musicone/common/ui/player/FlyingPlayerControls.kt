package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal class PlayerControlHandoff {
    private var frozenPlaying by mutableStateOf<Boolean?>(null)
    private val liveInk = mutableMapOf<String, Color>()
    private var frozenInk: Map<String, Color>? = null

    fun observeInk(key: String, color: Color) { liveInk[key] = color }
    fun sourceInk(key: String, fallback: Color): Color = frozenInk?.get(key) ?: liveInk[key] ?: fallback
    fun currentInk(key: String, fallback: Color): Color = liveInk[key] ?: fallback

    fun begin(playing: Boolean) {
        if (frozenPlaying == null) {
            frozenInk = liveInk.toMap()
            frozenPlaying = playing
        }
    }

    fun complete() {
        frozenPlaying = null
        frozenInk = null
    }

    fun displayedPlaying(livePlaying: Boolean): Boolean = frozenPlaying ?: livePlaying
}

@Composable
internal fun rememberPlayerControlHandoff(): PlayerControlHandoff = remember { PlayerControlHandoff() }

internal val LocalPlayerControlHandoff = staticCompositionLocalOf<PlayerControlHandoff?> { null }

@Composable
internal fun FlyingPlayerControls(motion: PageMotion, track: MusicTrack, playing: Boolean) {
    val handoff = LocalPlayerControlHandoff.current
    val textColor = handoff?.sourceInk("title", Color.Black) ?: Color.Black
    val secondary = handoff?.sourceInk("artist", Color.Black) ?: Color.Black
    val playColor = handoff?.sourceInk("play", Color.Black) ?: Color.Black
    val playerInk = playerForegroundColor()
    val playerSecondary = playerInk.copy(alpha = .62f)
    val rotation = LocalPhonePlayerRotation.current
    val targetRotation = if (LocalPhonePlayerFlightRotation.current != null) rotation else 0
    val titleGeometry = playerTextGeometry(motion, "title", track, targetRotation)
    val subtitleGeometry = playerTextGeometry(motion, "subtitle", track, targetRotation)
    val flight = LocalPhonePlayerFlightRotation.current
    val surfaceFrame = LocalPlayerSurfaceFlightFrame.current
    val titleFlight = remember(motion, flight, targetRotation, titleGeometry, surfaceFrame) {
        playerControlFlightState(motion, flight, targetRotation, "title", titleGeometry, surfaceFrame)
    }
    val subtitleFlight = remember(motion, flight, targetRotation, subtitleGeometry, surfaceFrame) {
        playerControlFlightState(motion, flight, targetRotation, "subtitle", subtitleGeometry, surfaceFrame)
    }
    val playFlight = remember(motion, flight, targetRotation) {
        playerControlFlightState(motion, flight, targetRotation, "play")
    }
    // 两端分别使用真实尺寸，避免对源字号再次套用目标容器的缩放。
    for (target in listOf(false, true)) {
        val titleSize = playerControlTextSize(
            if (target) motion.targetSnapshot["title"] ?: motion.targets["title"]
            else motion.sourceSnapshot["title"] ?: motion.sources["title"],
            if (target) MusicOneTextStyles.playerTitle.fontSize.value else MusicOneTextStyles.miniPlayerTitle.fontSize.value,
        )
        val subtitleSize = playerControlTextSize(
            if (target) motion.targetSnapshot["subtitle"] ?: motion.targets["subtitle"]
            else motion.sourceSnapshot["subtitle"] ?: motion.sources["subtitle"],
            if (target) MusicOneTextStyles.playerArtist.fontSize.value else MusicOneTextStyles.miniPlayerArtist.fontSize.value,
        )
        FlyingControl(motion, "title", target, titleFlight, geometry = titleGeometry) {
            if (target && motion.targetContentHandoff) {
                PlayerTextHandoffSnapshot("title")
            } else if (!target) {
                MiniPlayerText(playerPrimaryTitle(track.title), textColor, fontSize = titleSize.sp)
            } else {
                TrackTitle(
                    track = track,
                    fontSize = titleSize.sp,
                    style = (if (target) MusicOneTextStyles.playerTitle else MusicOneTextStyles.miniPlayerTitle)
                        .copy(fontSize = titleSize.sp),
                    color = if (target) playerInk else textColor,
                    overflow = TextOverflow.Ellipsis,
                    badgeOnDark = target && playerInk.luminance() > .5f,
                    showBadges = false,
                    primaryTitleOnly = true,
                    marquee = target,
                    marqueeRunning = false,
                )
            }
        }
        FlyingControl(motion, "subtitle", target, subtitleFlight, geometry = subtitleGeometry) {
            if (target && motion.targetContentHandoff) {
                PlayerTextHandoffSnapshot("subtitle")
            } else if (!target) {
                MiniPlayerText(playerPrimaryArtists(track.artists), playerInk,
                    Modifier.playerFlightTint(secondary, playerSecondary, motion), artist = true,
                    fontSize = subtitleSize.sp)
            } else {
                Text(musicOneUiAnnotatedString(playerPrimaryArtists(track.artists)),
                    style = (if (target) MusicOneTextStyles.playerArtist else MusicOneTextStyles.miniPlayerArtist)
                        .copy(fontSize = subtitleSize.sp),
                    color = playerInk, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(if (target) Modifier.basicMarquee(iterations = 0) else Modifier)
                        .playerFlightTint(secondary, playerSecondary, motion))
            }
        }
        FlyingControl(motion, "play", target, playFlight, Alignment.Center) {
            val targetMark = if (rotation != 0) (motion.targetSnapshot["play"] ?: motion.targets["play"])
                ?.markSize?.takeIf { it > 0f } ?: 54f else 54f
            Icon(if (playing) PlayerControlIcons.Pause else PlayerControlIcons.Play, null,
                tint = playerInk, modifier = Modifier.size((if (target) targetMark else 21f).dp)
                    .playerFlightTint(playColor, playerInk, motion))
        }
    }
}

internal fun playerControlTextSize(anchor: MotionAnchor?, fallback: Float): Float =
    anchor?.textSizeSp?.takeIf { it > 0f } ?: fallback

internal fun playerTextMarqueeEnabled(phase: MotionPhase, targetContentHandoff: Boolean = false): Boolean =
    phase == MotionPhase.SHOWN || targetContentHandoff

@Composable
private fun FlyingControl(motion: PageMotion, key: String, target: Boolean,
    trajectory: State<PhonePlayerFlightGeometry?>,
    alignment: Alignment = Alignment.CenterStart, geometry: PlayerTextGeometry? = null,
    content: @Composable BoxScope.() -> Unit) {
    // 准备阶段先挂载飞行控件，真正开始移动时只切换绘制，避免源控件先隐藏一帧。
    val source = geometry?.source ?: motion.sourceSnapshot[key] ?: motion.sources[key] ?: return
    val destination = geometry?.target ?: motion.targetSnapshot[key] ?: motion.targets[key] ?: return
    val flight = LocalPhonePlayerFlightRotation.current
    val rotation = if (flight != null) LocalPhonePlayerRotation.current else 0
    val endpoint = if (target && rotation != 0) destination.copy(bounds = phonePlayerFlightTarget(destination.bounds, rotation))
        else if (target) destination else source
    val density = LocalDensity.current
    val contentScale = endpoint.contentScale.coerceAtLeast(.01f)
    val width = endpoint.bounds.width.coerceAtLeast(1f) / contentScale
    val height = endpoint.bounds.height.coerceAtLeast(1f) / contentScale
    Box(Modifier.offset {
        IntOffset((endpoint.bounds.left - motion.hostBounds.left).roundToInt(),
            (endpoint.bounds.top - motion.hostBounds.top).roundToInt())
    }.size(with(density) { width.toDp() }, with(density) { height.toDp() }).graphicsLayer {
        val pose = trajectory.value
        if (pose == null) {
            alpha = 0f
        } else {
            val p = motion.value
            if (rotation != 0 || flight?.handoffActive == true) {
                val start = phonePlayerFlightRotate(pose.bounds.topLeft, pose.bounds.center, pose.rotation)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = start.x - endpoint.bounds.left
                translationY = start.y - endpoint.bounds.top
                scaleX = pose.bounds.height / height
                scaleY = scaleX
                rotationZ = pose.rotation
            } else {
            val bounds = pose.bounds
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = bounds.left - endpoint.bounds.left
            translationY = bounds.top - endpoint.bounds.top
            scaleX = bounds.height / height
            scaleY = scaleX
            }
            alpha = if (target) p else 1f - p
        }
    }, contentAlignment = alignment, content = content)
}
