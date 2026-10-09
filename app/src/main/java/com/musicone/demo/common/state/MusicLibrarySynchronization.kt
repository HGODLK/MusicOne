package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

internal val LocalMyPageVisible = compositionLocalOf { false }

/** 音频目标和浏览预览都在封面准备阶段同步；页面仅接入入口。 */
@Composable
internal fun MusicLibrarySynchronization(viewModel: MusicOneViewModel, favorites: MusicFavoriteViewModel,
    source: MusicSource, sessionRevision: Long, myVisible: Boolean,
    refreshLibrary: () -> Unit, cards: PlaylistCardTransition, rootVisible: Boolean) {
    LaunchedEffect(viewModel, source, sessionRevision) {
        launch {
            viewModel.favoritePreparation.target.collect { target ->
                if (target?.first?.source == source) favorites.refresh()
            }
        }
        launch {
            viewModel.rapidTrackSwitch.presentation.distinctUntilChangedBy { it?.token }.collect { target ->
                if (target?.track?.source == source) favorites.refresh()
            }
        }
    }
    LaunchedEffect(source, sessionRevision, myVisible) {
        if (myVisible) { favorites.refresh(); refreshLibrary() }
    }
    LaunchedEffect(rootVisible) { if (rootVisible) cards.releasePlaybackAfterReturn() }
}
