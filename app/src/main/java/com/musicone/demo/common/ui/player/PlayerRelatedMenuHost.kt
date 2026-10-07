package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 播放页拥有独立的关联详情栈，返回详情时回到原菜单，不收起播放器。 */
@Composable
internal fun PlayerRelatedMenuHost(state: MusicOneUiState, model: MusicOneViewModel,
    content: @Composable (Boolean) -> Unit) {
    val navigation = rememberEntityNavigation()
    val scope = rememberCoroutineScope()
    val rootMotion = remember { PageMotion(scope, false, 320, 280) }
    val rootTools = rememberPlaylistFloatingToolsOverlayState()
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val context = remember { MusicPlaylist("player-related", MusicSource.QQ, "当前歌曲", "", "", 0, 0L, 0L, "", emptyList()) }
    CompositionLocalProvider(LocalEntityNavigation provides navigation, LocalEntityFrame provides null) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().drawWithContent {
                if (navigation.pages.isNotEmpty()) {
                    backdrop.record { this@drawWithContent.drawContent() }; drawLayer(backdrop)
                } else drawContent()
            }) {
                QqPlaylistSongMenuHost(context, emptyList(), 0.dp, {}, {}, {}, relatedOnly = true,
                    liveBackdrop = navigation.pages.isNotEmpty()) {
                    val menu = LocalQqPlaylistSongMenu.current
                    content(menu?.expanded == true || navigation.pages.isNotEmpty())
                }
                CompositionLocalProvider(LocalPlayerMotion provides null) {
                    EntityPageHost(navigation, state, model, 0.dp, emptyList(), {})
                }
            }
            UnifiedPlaylistFloatingTools(rootTools, rootMotion, navigation, backdrop, bounds, 24.dp,
                Modifier.align(Alignment.BottomEnd))
            val top = navigation.pages.lastOrNull()
            val backButtonDistance = with(LocalDensity.current) { 76.dp.toPx() }
            val backButtonVisible = navigation.pages.size > 1 || top?.motion?.wantsOpen == true
            val backButtonProgress by animateFloatAsState(
                if (backButtonVisible) 1f else 0f,
                musicMotion(320),
                label = "播放关联返回按钮",
            )
            if (top != null) {
                val back = { (top.backAction ?: navigation::back)() }
                BackHandler(onBack = back)
            }
            if (top != null || backButtonProgress > 0f) MusicOneBackdropGlass(
                backdrop,
                bounds,
                Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp).size(48.dp),
                CircleShape,
                visualOffset = {
                    Offset(playlistBackButtonOffset(backButtonProgress, backButtonDistance), 0f)
                },
            ) {
                IconButton(
                    onClick = { top?.let { (it.backAction ?: navigation::back)() } },
                    enabled = backButtonVisible && top != null,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            }
        }
    }
}

internal fun Modifier.playerRelatedInformation(track: MusicTrack, enabled: Boolean): Modifier = composed {
    val menu = LocalQqPlaylistSongMenu.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var press by remember(track.id) { mutableStateOf<Offset?>(null) }
    onGloballyPositioned { bounds = it.boundsInRoot() }
        .pointerInput(track.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                press = bounds.topLeft + down.position
            }
        }
        .clickable(enabled = enabled && track.source == MusicSource.QQ && menu != null,
            onClickLabel = "查看歌手或专辑") {
            val point = press ?: bounds.center
            menu?.open(track, Rect(point.x - 1f, point.y - 1f, point.x + 1f, point.y + 1f))
        }
        .semantics { contentDescription = "查看歌手或专辑" }
}
