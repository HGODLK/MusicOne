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
    // 自然过渡途中才挂载时，直接从上一首色场按当前进度画出。
    var sweep by remember {
        mutableStateOf(autoMixInitialAtmosphereSweep(palette.identity, palette.colors) ?: AtmosphereSweep.still(palette.colors))
    }
    val clock = checkNotNull(LocalPlayerAtmosphereFrameClock.current)

    PlayerAtmosphereAnimationEffect(Unit) {
        // 普通切歌：固定时长扫动。
        suspend fun sweepTo(next: ArtworkAtmospherePalette) {
            val reduced = ExperiencePreferences.options.reduceMotion
            val incoming = sweep.next(next.colors, latestDirection(), settled = reduced)
            Snapshot.withMutableSnapshot { displayed = next; sweep = incoming }
            if (reduced) return
            clock.crossfade {
                animate(0f, ATMOSPHERE_SWEEP_TOTAL_MS,
                    animationSpec = tween(ATMOSPHERE_SWEEP_TOTAL_MS.toInt(), easing = LinearEasing)) { value, _ ->
                    incoming.elapsed.floatValue = value
                }
            }
            incoming.elapsed.floatValue = ATMOSPHERE_SWEEP_TOTAL_MS
        }
        // 自然过渡：换色跟随过渡时钟（低频背景时钟即可），被打断时从所见颜色改走普通扫动收尾。
        suspend fun followAutoMix(driven: AtmosphereSweep, next: ArtworkAtmospherePalette) {
            val drive = checkNotNull(driven.drive)
            // 与普通切歌一样临时切到全帧率，否则长达数十秒的换色只按常态八帧刷新，看起来一顿一顿
            if (clock.crossfade { drive.follow { driven.driveClock.longValue = it } }) {
                driven.elapsed.floatValue = ATMOSPHERE_SWEEP_TOTAL_MS
            } else sweepTo(next)
        }
        // 中断时从每个色团实际所见颜色出发，立即向实际切换后的目标衔接。
        snapshotFlow { latest }.collectLatest { next ->
            if (!artworkAtmospherePaletteChanged(
                    displayed.identity, displayed.colors, next.identity, next.colors,
                )) {
                val current = sweep
                if (current.drive != null && !current.settled) followAutoMix(current, next)
                return@collectLatest
            }
            val drive = autoMixVisualDriveFor(next.identity)
            if (drive == null) {
                sweepTo(next)
                return@collectLatest
            }
            // 方向固定为下一首：下方先变、上方后变。
            val incoming = sweep.next(next.colors, TrackTransitionDirection.NEXT, settled = false, drive = drive)
            Snapshot.withMutableSnapshot { displayed = next; sweep = incoming }
            followAutoMix(incoming, next)
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

