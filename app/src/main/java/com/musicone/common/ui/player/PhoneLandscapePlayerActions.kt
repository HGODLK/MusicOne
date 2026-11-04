package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** 竖屏底部入口位于横向画布右侧时，歌词让出整条入口区域及间距。 */
@Composable
internal fun phoneLandscapeLyricsEndInset(rotation: Int): Dp {
    if (rotation < 0) return 0.dp
    val density = LocalDensity.current
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val contentSide = phonePlayerLandscapePadding(rotation, balanceHorizontal = true)
        .calculateRightPadding(LayoutDirection.Ltr)
    return maxOf(0.dp, navigationBottom + 58.dp + 8.dp - contentSide)
}

internal val LocalPhonePlayerFixedActions = staticCompositionLocalOf { false }

/** 固定入口共用同一实例与物理位置，图标独立旋转，不参与正文渐隐。 */
@Composable
internal fun PhonePlayerFixedActions(rotation: Int, enabled: Boolean, showLyrics: Boolean,
    onLyrics: () -> Unit, onQueue: () -> Unit,
    lyricsProgress: () -> Float = { if (showLyrics) 1f else 0f },
    controls: PlayerControlsVisibilityState? = null) {
    val animatedRotation = LocalPhonePlayerPresentation.current?.actionRotation
    val density = LocalDensity.current
    val travel = maxOf(controls?.travelPx ?: 0f,
        with(density) { 58.dp.toPx() } + WindowInsets.navigationBars.getBottom(density))
    val controlsHidden by remember(controls) { derivedStateOf { (controls?.progress ?: 0f) >= 1f } }
    val interactive = enabled && !controlsHidden
    // 入场动画期间只是暂不接收点击，图标保持正常颜色。
    val buttonColors = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current)
    PhonePlayerWindowHost(-rotation) {
        Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            PhoneTurnElement("fixedActions", Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = CompactPlayerLayoutSpec.horizontalPadding).height(58.dp),
                scaleContent = false, physicalRotation = 0,
                physicalOffset = { Offset(0f, playerControlsTranslation(controls?.progress ?: 0f, travel)) },
                physicalVelocity = { Offset(0f, (controls?.velocity ?: 0f) * travel) }) {
                Row(Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onLyrics, Modifier.size(width = 64.dp, height = 56.dp).semantics {
                        contentDescription = if (showLyrics) "切换为时钟" else "切换为歌词"
                    }, enabled = interactive, colors = buttonColors) {
                        LyricsToggleIcon(lyricsProgress,
                            Modifier.size(28.dp).graphicsLayer { rotationZ = animatedRotation?.value ?: rotation.toFloat() })
                    }
                    IconButton(onQueue, Modifier.size(width = 64.dp, height = 56.dp), enabled = interactive, colors = buttonColors) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放列表",
                            Modifier.size(28.dp).graphicsLayer { rotationZ = animatedRotation?.value ?: rotation.toFloat() })
                    }
                }
            }
        }
    }
}
