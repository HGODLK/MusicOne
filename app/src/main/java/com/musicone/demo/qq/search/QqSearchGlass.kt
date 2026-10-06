package com.musicone.demo

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** 与播放页一样保持背景纹理坐标固定，仅由外层裁剪轮廓展开，不拉伸玻璃。 */
@Composable
internal fun QqSearchGlass(backdrop: GraphicsLayer, bounds: Rect, motion: QqSearchMotion,
    dim: () -> Float = { 0f }, tint: () -> Color) {
    val fallback = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
    val texture = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().drawWithCache {
        val factor = playerGlassSampleScale(size.width.toInt(), size.height.toInt())
        val snapshot = motion.backdropSnapshot
        // 准备中沿用原玻璃，像素快照就绪后再交接；读取失败才使用底色，避免准备首帧白闪。
        val frozen = motion.snapshotUnavailable || snapshot != null
        texture.renderEffect = if (!ExperiencePreferences.options.disableBlur && Build.VERSION.SDK_INT >= 31) BlurEffect(18.dp.toPx() * factor,
            18.dp.toPx() * factor, TileMode.Clamp) else null
        texture.record(size = IntSize((size.width * factor).toInt().coerceAtLeast(1),
            (size.height * factor).toInt().coerceAtLeast(1))) {
            drawRect(fallback)
            scale(factor, factor, androidx.compose.ui.geometry.Offset.Zero) {
                // 根内容与搜索覆盖层均从状态栏下方开始，横坐标按根内容实际位置对齐。
                translate(left = bounds.left) {
                    if (!ExperiencePreferences.options.disableBlur) {
                        if (snapshot != null) drawImage(snapshot)
                        else if (!frozen) drawLayer(backdrop)
                    }
                }
            }
        }
        onDrawBehind {
            scale(1f / factor, 1f / factor, androidx.compose.ui.geometry.Offset.Zero) { drawLayer(texture) }
            drawRect(tint())
            // 输入时只叠加中性黑色，保持当前模糊背景的色相。
            drawRect(Color.Black.copy(alpha = dim().coerceIn(0f, 1f)))
        }
    })
}

@Composable
internal fun QqSearchTheme(content: @Composable () -> Unit) {
    PlatformMusicTheme(MusicSource.QQ, neutral = false, content = content)
}
