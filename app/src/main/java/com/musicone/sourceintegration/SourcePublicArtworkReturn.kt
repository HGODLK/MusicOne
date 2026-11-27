package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** 嵌入页返回时同步释放冻结的封面，保持和根页一致的返回动画。 */
@Composable
internal fun BindSourcePublicArtworkReturn(
    scene: SourcePublicScene,
    chrome: SourcePublicSceneBridge,
    interactive: Boolean,
) {
    val rootVisible = interactive && !scene.detailMounted &&
        !chrome.playerMotion.mounted && !chrome.settingsMotion.mounted
    val cards = scene.playlists.cards
    LaunchedEffect(cards, rootVisible) {
        if (rootVisible) cards.releasePlaybackAfterReturn()
    }
}
