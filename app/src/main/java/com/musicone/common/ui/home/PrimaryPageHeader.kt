package com.musicone

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

@Composable
internal fun PrimaryPageHeader(
    page: MusicOnePage,
    pagerPosition: () -> Float,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    motionVisibility: () -> Float,
    modifier: Modifier = Modifier,
    searchProgress: () -> Float = { 0f },
    settingsProgress: () -> Float = { 0f },
    settingsMounted: () -> Boolean = { false },
    onSettingsBounds: (Rect) -> Unit = {},
    contentColor: Color? = null,
    localMode: Boolean = LocalMusicLibraryUi.current.localMode,
    showAction: Boolean = true,
) {
    val headerState = LocalPrimaryPageHeaderState.current
    val resolvedContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val immersiveContainer = if (resolvedContentColor.luminance() > .5f) {
        Color.Black.copy(alpha = .34f)
    } else {
        Color.White.copy(alpha = .42f)
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(68.dp).graphicsLayer {
            val visibility = motionVisibility().coerceIn(0f, 1f)
            alpha = visibility
            translationY = -size.height * (1f - visibility)
        },
    ) {
        val gutter = if (maxWidth >= 600.dp) maxOf(32.dp, (maxWidth - 1120.dp) / 2) else 20.dp
        val travel = with(LocalDensity.current) { 32.dp.toPx() }
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight().semantics {
                    if (headerState.titleVisibility(page) > 0f) {
                        contentDescription = if (localMode) "本地音乐" else page.primaryTitle
                    } else {
                        hideFromAccessibility()
                    }
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                MusicOnePage.entries.forEach { targetPage ->
                    Text(
                        if (localMode) "本地音乐" else targetPage.primaryTitle,
                        color = resolvedContentColor,
                        fontSize = 34.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.1).sp,
                        modifier = Modifier.graphicsLayer {
                            val motion = pageTitleMotion(targetPage.ordinal, pagerPosition())
                            val verticalVisibility = headerState.titleVisibility(targetPage)
                            translationX = motion.offsetFraction * travel
                            val search = maxOf(searchProgress(), settingsProgress())
                            translationY = -12.dp.toPx() * (1f - verticalVisibility) - 40.dp.toPx() * search
                            alpha = motion.alpha * verticalVisibility * (1f - search)
                        }.clearAndSetSemantics {},
                    )
                }
            }
            if (showAction) PrimaryPageActionButton(page, pagerPosition, backdropLayer, backdropBounds, onSearch, onSettings,
                localMode, searchProgress, settingsProgress, settingsMounted, onSettingsBounds, contentColor)
        }
    }
}

private val MusicOnePage.primaryTitle: String
    get() = when (this) {
        MusicOnePage.HOME -> "首页"
        MusicOnePage.MY -> "我的"
    }

internal fun primaryHeaderMotionVisibility(playlistProgress: Float, playerProgress: Float): Float =
    1f - maxOf(playlistProgress, playerProgress).coerceIn(0f, 1f)
