package com.musicone.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

internal data class PlayerTextIdentity(
    val source: MusicSource, val id: String, val title: String, val artists: String,
)

internal fun MusicTrack.playerTextIdentity() = PlayerTextIdentity(
    source, id, playerPrimaryTitle(title), playerPrimaryArtists(artists),
)

internal data class PlayerTextFrame(val identity: PlayerTextIdentity, val size: IntSize)
internal data class PlayerTextGeometry(
    val source: MotionAnchor,
    val target: MotionAnchor,
    private val capturedSource: MotionAnchor,
    private val capturedTarget: MotionAnchor,
) {
    // 退场可先于重组开始，旧尺寸计算不能覆盖本次刚捕获的歌词页眉位置。
    fun sourceFor(anchor: MotionAnchor): MotionAnchor = if (anchor == capturedSource) source else anchor
    fun targetFor(anchor: MotionAnchor): MotionAnchor = if (anchor == capturedTarget) target else anchor
}

/** 仅更新文字尺寸，位置、字号及其余飞行锚点仍沿用当前页面事务。 */
@Composable
internal fun playerTextGeometry(motion: PageMotion, key: String, track: MusicTrack): PlayerTextGeometry? {
    val source = (if (motion.moving) motion.sourceSnapshot[key] else motion.sources[key])
        ?: motion.sourceSnapshot[key] ?: return null
    val target = (if (motion.moving) motion.targetSnapshot[key] else motion.targets[key])
        ?: motion.targetSnapshot[key] ?: return null
    val handoff = LocalPlayerTextHandoff.current
    val identity = track.playerTextIdentity()
    val frame = handoff?.frames?.get(key)
    var initialIdentity by remember(motion.sourceSnapshot, key) { mutableStateOf(frame?.identity ?: identity) }
    var adapting by remember(motion.sourceSnapshot, key) { mutableStateOf(false) }
    val changed = adapting || motion.moving && identity != initialIdentity
    SideEffect { if (motion.moving && identity != initialIdentity) adapting = true }

    fun nativeWidth(anchor: MotionAnchor) = anchor.bounds.width / anchor.contentScale.coerceAtLeast(.01f)
    fun nativeHeight(anchor: MotionAnchor) = anchor.bounds.height / anchor.contentScale.coerceAtLeast(.01f)
    val ready = (changed || !motion.moving) && frame?.identity == identity
    val spec = if (changed) musicMotion(220) else snap<Float>()
    val sourceWidth by animateFloatAsState(
        if (ready) nativeWidth(motion.sources[key] ?: source) else nativeWidth(source), spec, label = "源文字宽度")
    val sourceHeight by animateFloatAsState(
        if (ready) nativeHeight(motion.sources[key] ?: source) else nativeHeight(source), spec, label = "源文字高度")
    val targetWidth by animateFloatAsState(
        if (ready) frame.size.width.toFloat() else nativeWidth(target), spec, label = "目标文字宽度")
    val targetHeight by animateFloatAsState(
        if (ready) frame.size.height.toFloat() else nativeHeight(target), spec, label = "目标文字高度")
    SideEffect {
        // 飞行层退出后继续收敛同一宽度，真实文字不能突然露出尚未展开的尾部。
        if (changed && ready && abs(targetWidth - frame.size.width) > .5f) handoff.clipWidths[key] = targetWidth
        else handoff?.clipWidths?.remove(key)
        if (!motion.moving && (!changed || ready && abs(targetWidth - frame.size.width) <= .5f &&
                abs(targetHeight - frame.size.height) <= .5f)) {
            initialIdentity = identity
            adapting = false
        }
    }
    fun sized(anchor: MotionAnchor, width: Float, height: Float) = anchor.copy(bounds = Rect(
        anchor.bounds.left, anchor.bounds.top,
        anchor.bounds.left + width.coerceAtLeast(1f) * anchor.contentScale,
        anchor.bounds.top + height.coerceAtLeast(1f) * anchor.contentScale,
    ))
    return PlayerTextGeometry(sized(source, sourceWidth, sourceHeight), sized(target, targetWidth, targetHeight),
        source, target)
}
