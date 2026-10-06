package com.musicone.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp

internal val LocalUnifiedBack = staticCompositionLocalOf { false }

/** 唯一返回控件留在录制层之外，子页换页只替换动作，不重建按钮。 */
@Composable
internal fun UnifiedBackButton(entities: EntityNavigation, settings: SettingsNavigation,
    playlist: RecommendationNavigation, toolbar: PlaylistToolbarOverlayState,
    search: QqSearchState, searchModel: QqSearchViewModel, searchMotion: QqSearchMotion,
    player: PageMotion, backdrop: GraphicsLayer, bounds: Rect) {
    val top = entities.pages.lastOrNull()
    val visible = settings.destination != SettingsDestination.CLOSED ||
        entities.pages.size > 1 || top?.motion?.wantsOpen == true ||
        playlist.motion.wantsOpen || search.full
    val progress = animateFloatAsState(if (visible) 1f else 0f, musicMotion(320), label = "通用返回按钮")
    val statusInsets = WindowInsets.statusBars
    if (progress.value == 0f && !visible) return
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, top = 12.dp)) {
        MusicOneBackdropGlass(backdrop, bounds, Modifier.size(48.dp).graphicsLayer {
            translationX = -76.dp.toPx() * (1f - progress.value)
            translationY = -(statusInsets.getTop(this) + 76.dp.toPx()) * player.value
        }, CircleShape) {
            IconButton(onClick = {
                when {
                    settings.destination != SettingsDestination.CLOSED -> settings.back()
                    top != null -> (top.backAction ?: entities::back)()
                    entities.rootMenu?.expanded == true -> entities.rootMenu?.back()
                    playlist.motion.mounted -> toolbar.back()
                    search.opened || searchMotion.mounted -> searchModel.back()
                }
            }, enabled = visible && !player.mounted, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
