package com.musicone

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest

private data class ArtworkAtmospherePalette(
    val identity: ArtworkIdentity,
    val colors: IntArray,
)

/** 单通道混合九个采样色，切歌期间不再同时绘制两套全屏色场；换色沿屏幕视觉纵向错开扫动。 */
@Composable
internal fun ArtworkAtmosphereTransition(
    frame: PlayerArtworkFrame,
    phaseState: PlayerAtmosphereMotionState,
    direction: () -> TrackTransitionDirection,
    rotationDegrees: () -> Int,
    modifier: Modifier = Modifier,
) {
    val palette = remember(frame) {
        ArtworkAtmospherePalette(
            frame.identity,
            ArtworkColorFieldRepository.peek(frame.identity)
                ?: ArtworkColorFieldRepository.prepare(frame.identity, frame.bitmap)
                ?: ArtworkColorSampler.placeholder(frame.identity),
        )
    }
    val latest by rememberUpdatedState(palette)
    val latestDirection by rememberUpdatedState(direction)
    var displayed by remember { mutableStateOf(palette) }
    var sweep by remember { mutableStateOf(AtmosphereSweep.still(palette.colors)) }
    val clock = checkNotNull(LocalPlayerAtmosphereFrameClock.current)

    PlayerAtmosphereAnimationEffect(Unit) {
        // 中断时从每个色团实际所见颜色出发，立即向实际切换后的目标衔接。
        snapshotFlow { latest }.collectLatest { next ->
            if (!artworkAtmospherePaletteChanged(
                    displayed.identity, displayed.colors, next.identity, next.colors,
                )) return@collectLatest
            val reduced = ExperiencePreferences.options.reduceMotion
            val incoming = sweep.next(next.colors, latestDirection(), settled = reduced)
            Snapshot.withMutableSnapshot { displayed = next; sweep = incoming }
            if (reduced) return@collectLatest
            clock.crossfade {
                animate(0f, ATMOSPHERE_SWEEP_TOTAL_MS,
                    animationSpec = tween(ATMOSPHERE_SWEEP_TOTAL_MS.toInt(), easing = LinearEasing)) { value, _ ->
                    incoming.elapsed.floatValue = value
                }
            }
            incoming.elapsed.floatValue = ATMOSPHERE_SWEEP_TOTAL_MS
        }
    }

    ArtworkAtmosphereBlobs(
        sweep = { sweep },
        rotationDegrees = rotationDegrees,
        phaseState = phaseState,
        modifier = modifier,
    )
}

internal fun artworkAtmospherePaletteChanged(
    displayedIdentity: ArtworkIdentity,
    displayedColors: IntArray,
    nextIdentity: ArtworkIdentity,
    nextColors: IntArray,
): Boolean = displayedIdentity != nextIdentity || !displayedColors.contentEquals(nextColors)

