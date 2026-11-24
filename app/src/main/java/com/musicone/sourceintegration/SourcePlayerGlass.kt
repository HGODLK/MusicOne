package com.musicone.sourceintegration

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.musicone.*
import kotlin.math.roundToInt

/**
 * 自建播放页的原生展开玻璃：铺在包页面下方，背后压暗与原生一致。
 * 原生玻璃从迷你条背景模糊过渡到播放页背景模糊；包页面在独立 View 里无法录制，
 * 目标端改用封面铺满加原生同款压暗录成静态图层，由玻璃自己模糊和淡入。
 */
@Composable
internal fun SourcePlayerGlassLayer(motion: PageMotion, backdrop: PlayerTransitionBackdrop?, track: MusicTrack) {
    val target = rememberGraphicsLayer()
    val frame by rememberPlayerArtwork(track)
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val recorded = remember { arrayOfNulls<Any>(1) }
    Box(Modifier.fillMaxSize()) {
        if (motion.moving || motion.phase == MotionPhase.PREPARING) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.value }.background(Color.Black.copy(alpha = .22f)))
        }
        // 只录制不绘制；封面或尺寸变了才重录，展开途中不逐帧重建。
        Spacer(Modifier.fillMaxSize().drawBehind {
            val key = listOf(frame.identity, frame.bitmap, size, dark)
            if (recorded[0] != key && size.width >= 1f && size.height >= 1f) {
                recorded[0] = key
                recordSourcePlayerGlassTarget(target, frame.bitmap, dark)
            }
        })
        CompositionLocalProvider(
            LocalPlayerAtmosphereTexture provides target,
            LocalPlayerTransitionBackdrop provides (backdrop ?: LocalPlayerTransitionBackdrop.current),
        ) { FlyingPlayerGlass(motion) }
    }
}

private fun DrawScope.recordSourcePlayerGlassTarget(layer: GraphicsLayer, bitmap: Bitmap?, dark: Boolean) {
    layer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            // 按铺满裁切：取与页面同比例的中心区域。
            val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
            val srcWidth = (size.width / scale).roundToInt().coerceIn(1, bitmap.width)
            val srcHeight = (size.height / scale).roundToInt().coerceIn(1, bitmap.height)
            drawImage(bitmap.asImageBitmap(),
                srcOffset = IntOffset((bitmap.width - srcWidth) / 2, (bitmap.height - srcHeight) / 2),
                srcSize = IntSize(srcWidth, srcHeight),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        } else drawRect(if (dark) Color(0xFF242526) else Color(0xFF5A5E66))
        // 与原生播放页色场上的压暗层相同。
        drawRect(Brush.verticalGradient(
            if (dark) listOf(Color.Black.copy(alpha = .5f), Color.Black.copy(alpha = .7f))
            else listOf(Color.Black.copy(alpha = .3f), Color.Black.copy(alpha = .51f)),
            endY = size.height), size = Size(size.width, size.height))
    }
}
