package com.musicone.demo

import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

private data class ArtworkAtmospherePalette(
    val identity: ArtworkIdentity,
    val colors: IntArray,
)

/** 单通道混合九个采样色，切歌期间不再同时绘制两套全屏色场。 */
@Composable
internal fun ArtworkAtmosphereTransition(
    frame: PlayerArtworkFrame,
    phaseState: PlayerAtmosphereMotionState,
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
    var displayed by remember { mutableStateOf(palette) }
    var incoming by remember { mutableStateOf<AtmosphereBlend?>(null) }

    LaunchedEffect(Unit) {
        // 中断时保留当前混合色，立即向实际切换后的目标衔接。
        snapshotFlow { latest }.collectLatest { next ->
            incoming?.let { previous ->
                val colors = IntArray(displayed.colors.size) { index ->
                    lerp(Color(displayed.colors[index]), Color(previous.palette.colors[index]),
                        previous.progress.floatValue).toArgb()
                }
                Snapshot.withMutableSnapshot {
                    displayed = ArtworkAtmospherePalette(previous.palette.identity, colors)
                    incoming = null
                }
            }
            if (artworkAtmospherePaletteChanged(
                    displayed.identity, displayed.colors, next.identity, next.colors,
                )) {
                val blend = AtmosphereBlend(next, mutableFloatStateOf(0f))
                incoming = blend
                animate(0f, 1f, animationSpec = musicMotion<Float>(900)) { value, _ -> blend.progress.floatValue = value }
                Snapshot.withMutableSnapshot { displayed = next; incoming = null }
            }
        }
    }

    val blend = incoming
    val target = blend?.palette ?: displayed
    ArtworkAtmosphereBlobs(
        fromColors = displayed.colors,
        toColors = target.colors,
        transitionProgress = { blend?.progress?.floatValue ?: 1f },
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

private data class AtmosphereBlend(val palette: ArtworkAtmospherePalette, val progress: MutableFloatState)
