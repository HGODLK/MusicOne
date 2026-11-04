package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** 嵌入页返回时释放自己的封面冻结，沿用根页的等待和混合，不改变原动效。 */
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
