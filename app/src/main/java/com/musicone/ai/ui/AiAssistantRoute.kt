package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** 流式输出只在这里订阅，避免逐字刷新把整个应用根页面一起带动重组。 */
@Composable
internal fun AiAssistantRoute(controller: AiAssistantController, onBack: () -> Unit, bottomInset: Dp) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playbackFlow = remember(controller) {
        controller.playbackState?.map { AiCardPlayback(it.currentTrack?.id, it.currentTrack?.source, it.isPlaying) }
            ?.distinctUntilChanged()
    }
    val playback = playbackFlow?.collectAsStateWithLifecycle(AiCardPlayback())?.value ?: AiCardPlayback()
    val navigation = rememberRecommendationNavigation()
    val composerLayer = rememberGraphicsLayer()
    val artworkLayer = rememberGraphicsLayer()
    val composerBackdrop = remember(composerLayer, artworkLayer) { AiComposerBackdrop(composerLayer, artworkLayer) }
    LaunchedEffect(state.openedPlaylist?.id) {
        val target = state.openedPlaylist
        if (target == null) navigation.close()
        else if (navigation.playlist?.id != target.id || !navigation.motion.wantsOpen) navigation.open(target)
        else navigation.update(target)
    }
    val close = { navigation.close { controller.dispatch(AiUiAction.ClosePlaylist) } }
    AiMarkdownEnvironment {
    CompositionLocalProvider(LocalPlaylistMotion provides navigation.motion,
        LocalPlaylistCardTransition provides navigation.cards,
        LocalAiComposerBackdrop provides composerBackdrop,
        LocalAiCardPlayback provides playback) {
        AiGlassOverlayHost {
            Box(Modifier.fillMaxSize()) {
                AiAssistantScreen(state, controller::dispatch, onBack, controller::playTrack, bottomInset,
                    onOpenPlaylist = { playlist ->
                        controller.dispatch(AiUiAction.OpenPlaylist(playlist)); navigation.open(playlist)
                    })
                // 没打开歌单详情时不挂这层全屏节点，避免挡住顶栏设置按钮的点击。
                if (navigation.motion.mounted) navigation.playlist?.let { playlist ->
                    AiPlaylistDetailRoute(playlist, controller, bottomInset, navigation.motion, close)
                }
            }
        }
    }
    }
}
