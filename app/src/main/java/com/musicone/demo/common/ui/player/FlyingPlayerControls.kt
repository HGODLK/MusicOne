package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
        FlyingControl(motion, "title", target) {
            if (target && motion.targetContentHandoff) {
                PlayerTextHandoffSnapshot("title")
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
        FlyingControl(motion, "subtitle", target) {
            if (target && motion.targetContentHandoff) {
                PlayerTextHandoffSnapshot("subtitle")
            } else {
                Text(musicOneUiAnnotatedString(playerPrimaryArtists(track.artists)),
                    style = (if (target) MusicOneTextStyles.playerArtist else MusicOneTextStyles.miniPlayerArtist)
                        .copy(fontSize = subtitleSize.sp),
                    color = playerInk, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(if (target) Modifier.basicMarquee(iterations = 0) else Modifier)
                        .playerTint(secondary, playerSecondary, motion))
            }
        }
        FlyingControl(motion, "play", target, Alignment.Center) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null,
                tint = playerInk, modifier = Modifier.size((if (target) 54 else 21).dp)
                    .playerTint(playColor, playerInk, motion))
        }
    }
}

internal fun playerControlTextSize(anchor: MotionAnchor?, fallback: Float): Float =
    anchor?.textSizeSp?.takeIf { it > 0f } ?: fallback

internal fun playerTextMarqueeEnabled(phase: MotionPhase, targetContentHandoff: Boolean = false): Boolean =
    phase == MotionPhase.SHOWN || targetContentHandoff

private fun Modifier.playerTint(from: Color, to: Color, motion: PageMotion): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(lerp(from, to, playerMaterialProgress(motion.value)), blendMode = BlendMode.SrcIn)
    }

@Composable
private fun FlyingControl(motion: PageMotion, key: String, target: Boolean,
    alignment: Alignment = Alignment.CenterStart, content: @Composable BoxScope.() -> Unit) {
    // 准备阶段先挂载飞行控件，真正开始移动时只切换绘制，避免源控件先隐藏一帧。
    val source = motion.sourceSnapshot[key] ?: motion.sources[key] ?: return
    val destination = motion.targetSnapshot[key] ?: motion.targets[key] ?: return
    val endpoint = if (target) destination else source
    val density = LocalDensity.current
    val contentScale = endpoint.contentScale.coerceAtLeast(.01f)
    val width = endpoint.bounds.width.coerceAtLeast(1f) / contentScale
    val height = endpoint.bounds.height.coerceAtLeast(1f) / contentScale
    Box(Modifier.offset {
        IntOffset((endpoint.bounds.left - motion.hostBounds.left).roundToInt(),
            (endpoint.bounds.top - motion.hostBounds.top).roundToInt())
    }.size(with(density) { width.toDp() }, with(density) { height.toDp() }).graphicsLayer {
        val activeSource = motion.sourceSnapshot[key]
        val activeDestination = motion.targetSnapshot[key]
        if (!motion.moving || activeSource == null || activeDestination == null) {
            alpha = 0f
        } else {
            val p = motion.value
            val bounds = motionRect(activeSource.bounds, activeDestination.bounds, p)
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = bounds.left - endpoint.bounds.left
            translationY = bounds.top - endpoint.bounds.top
            scaleX = bounds.height / height
            scaleY = scaleX
            alpha = if (target) p else 1f - p
        }
    }, contentAlignment = alignment, content = content)
}
