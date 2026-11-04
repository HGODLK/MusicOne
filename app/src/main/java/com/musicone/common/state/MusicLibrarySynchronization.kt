package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalContext
import com.musicone.sourceintegration.SourcePackageController

internal val LocalMyPageVisible = compositionLocalOf { false }

/** 音频目标和浏览预览都在封面准备阶段同步；页面仅接入入口。 */
@Composable
internal fun MusicLibrarySynchronization(viewModel: MusicPlaybackRuntime, favorites: MusicFavoriteViewModel,
    source: MusicSource, sessionRevision: Long, myVisible: Boolean,
    refreshLibrary: () -> Unit, cards: PlaylistCardTransition, rootVisible: Boolean) {
    val sources = SourcePackageController.get(LocalContext.current)
    LaunchedEffect(viewModel, source, sessionRevision) {
        sources.startup.first { !it.initializing }
        launch {
            viewModel.favoritePreparation.target.collect { target ->
                if (target != null) favorites.refresh()
            }
        }
        launch {
            viewModel.rapidTrackSwitch.presentation.distinctUntilChangedBy { it?.token }.collect { target ->
                if (target != null) favorites.refresh()
            }
        }
    }
    LaunchedEffect(source, sessionRevision, myVisible) {
        sources.startup.first { !it.initializing }
        if (myVisible) { favorites.refresh(); refreshLibrary() }
    }
    LaunchedEffect(rootVisible) { if (rootVisible) cards.releasePlaybackAfterReturn() }
}
