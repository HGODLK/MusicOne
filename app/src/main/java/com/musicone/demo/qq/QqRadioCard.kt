package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 深绿海报以斜向声波形成构图，爱心、大字标题和状态图形保持稳定。 */
@Composable
internal fun QqRadioCard(active: Boolean, playing: Boolean, loading: Boolean, visible: Boolean,
    onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val palette = qqRadioCardPalette()
    Surface(modifier = modifier.fillMaxHeight().clip(shape).clickable(
        onClickLabel = if (active) "打开猜你喜欢" else "播放猜你喜欢", onClick = onClick,
    ), shape = shape, color = palette.background) {
        Box(Modifier.fillMaxSize()) {
            QqRadioWaveArtwork(active && playing, visible, palette.wave, Modifier.fillMaxSize())
            Icon(Icons.Default.Favorite, null, tint = palette.foreground,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 31.dp).size(46.dp))
            Surface(shape = CircleShape, color = palette.badge,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    QqRadioPlaybackIndicator(active, playing, loading, visible, palette.badgeInk)
                }
            }
            Text("猜你喜欢", modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 19.dp),
                color = palette.foreground,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 28.sp, stepSize = 1.sp),
                fontWeight = FontWeight.Bold, lineHeight = 1.15.em, letterSpacing = (-.5).sp,
                maxLines = 1, softWrap = false)
        }
    }
}
