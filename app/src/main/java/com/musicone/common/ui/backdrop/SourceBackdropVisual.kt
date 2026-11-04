package com.musicone

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class SourceBackdropVisual(
    val bitmap: Bitmap?,
    val foreground: Color,
    val secondary: Color,
    val tertiary: Color,
    val panelColor: Color,
    val panelBorder: Color,
    val protectionColor: Color,
    val useDarkForeground: Boolean,
)

/** 只向需要沉浸背景的页面传递，避免污染设置、搜索等普通页面的字体颜色。 */
internal val LocalSourceBackdropVisual = staticCompositionLocalOf<SourceBackdropVisual?> { null }

@Composable
internal fun SourceBackdropForeground(
    visual: SourceBackdropVisual?,
    content: @Composable () -> Unit,
) {
    if (visual == null) {
        content()
        return
    }
    val readableSurface = if (visual.useDarkForeground) Color(0xFFF7F7F8) else Color(0xFF1D1F23)
    val readableSurfaceVariant = if (visual.useDarkForeground) Color(0xFFE7E8EA) else Color(0xFF303238)
    val colors = MaterialTheme.colorScheme.copy(
        surface = readableSurface,
        surfaceVariant = readableSurfaceVariant,
        surfaceContainer = readableSurface,
        surfaceContainerHigh = readableSurfaceVariant,
        onBackground = visual.foreground,
        onSurface = visual.foreground,
        onSurfaceVariant = visual.secondary,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides visual.foreground, content = content)
    }
}

@Composable
internal fun rememberSourceBackdropVisual(
    customBackgroundUri: String? = null,
    customBackgroundRevision: Int = 0,
    foregroundArgb: Long? = null,
): SourceBackdropVisual {
    val imageUrl = sourceBackdropImageUri(customBackgroundUri)
    val bitmap by rememberArtworkBitmap(
        imageUrl,
        refreshKey = customBackgroundRevision.takeIf { customBackgroundUri != null && it > 0 },
        maxSide = 1_600,
    )
    val fallbackLuminance = MaterialTheme.colorScheme.background.luminance()
    val luminance = remember(bitmap, fallbackLuminance) {
        bitmap?.profileAverageLuminance() ?: fallbackLuminance
    }
    val useDarkForeground = foregroundArgb?.let { Color(it).luminance() < .5f } ?: (luminance >= .62f)
    val targetForeground = foregroundArgb?.let { Color(it) } ?: if (useDarkForeground) Color(0xFF101318) else Color.White
    val targetPanel = if (useDarkForeground) Color.White.copy(alpha = .38f)
        else Color.Black.copy(alpha = .3f)
    val targetProtection = if (useDarkForeground) Color.White else Color.Black
    val foreground by animateColorAsState(targetForeground, musicMotion(420), label = "背景文字对比度")
    val panelColor by animateColorAsState(targetPanel, musicMotion(420), label = "背景卡片对比度")
    val protectionColor by animateColorAsState(targetProtection, musicMotion(420), label = "背景保护层")
    return remember(bitmap, foreground, panelColor, protectionColor, useDarkForeground) {
        SourceBackdropVisual(
            bitmap = bitmap,
            foreground = foreground,
            secondary = foreground.copy(alpha = .78f),
            tertiary = foreground.copy(alpha = .6f),
            panelColor = panelColor,
            panelBorder = foreground.copy(alpha = .18f),
            protectionColor = if (useDarkForeground) Color.White else Color.Black,
            useDarkForeground = useDarkForeground,
        )
    }
}

@Composable
internal fun SourceBackdrop(
    visual: SourceBackdropVisual,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Crossfade(
            targetState = visual.bitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = musicMotion(450),
            label = "我的页背景交接",
        ) { bitmap ->
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .16f else .3f),
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .08f else .16f),
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .24f else .48f),
                        visual.protectionColor.copy(alpha = if (visual.useDarkForeground) .48f else .68f),
                    ),
                ),
            ),
        )
    }
}

private fun Bitmap.profileAverageLuminance(): Float {
    val samples = ArtworkColorSampler.sampleBitmap(this)
    if (samples.isEmpty()) return 0f
    return samples.sumOf { Color(it).luminance().toDouble() }.div(samples.size).toFloat()
}
