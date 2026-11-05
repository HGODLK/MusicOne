package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 手机和平板沿用已有收藏夹网格、封面渐变与独立播放按钮。 */
@Composable
internal fun SupplementalLibraryHome(state: SupplementalState, model: SupplementalSourceModel,
    scroll: LazyGridState, bottomInset: Dp, foldersOnly: Boolean = false) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = mainSourceLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = bottomInset + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!foldersOnly) {
                if ((model.entry.authentication != null || model.pageProvider != null) && model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.ACCOUNT)) item("account", span = { GridItemSpan(maxLineSpan) }) {
                    SupplementalAccountHeader(state.account, model.entry.descriptor.name) { model.open(SupplementalPage.ACCOUNT) }
                }
                if (model.pageProvider != null && model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.LINK)) item("link-page", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { model.open(SupplementalPage.LINK) }) { Text("链接导入") }
                } else if (model.pageProvider == null && model.entry.contents != null) item("link", span = { GridItemSpan(maxLineSpan) }) { SupplementalLinkEntry(state, model) }
                if (model.pageProvider?.supplementalPagePolicy(com.musicone.source.sdk.SourceSupplementalPage.COLLECTIONS)?.mode == com.musicone.source.sdk.SourceSupplementalPageMode.CUSTOM) item("collections-page", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { model.open(SupplementalPage.FOLDERS) }) { Text("音乐收藏夹") }
                }
            }
            if (!foldersOnly && model.pageProvider != null && model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.SETTINGS)) item("settings", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { model.open(SupplementalPage.SETTINGS) }) { Text("音源设置") }
            }
            state.error?.let { item("action-error", span = { GridItemSpan(maxLineSpan) }) { MainSourceInlineMessage(it) } }
            state.message?.let { item("action-message", span = { GridItemSpan(maxLineSpan) }) { MainSourceInlineMessage(it) } }
            if (model.entry.remoteCollections != null && model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.COLLECTIONS) &&
                (foldersOnly || model.pageProvider?.supplementalPagePolicy(com.musicone.source.sdk.SourceSupplementalPage.COLLECTIONS)?.mode != com.musicone.source.sdk.SourceSupplementalPageMode.CUSTOM)) {
                item("heading", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("音乐收藏夹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { model.loadCollections() }, enabled = !state.loadingDirectory) { Icon(Icons.Default.Refresh, "刷新收藏夹") }
                    }
                }
                if (state.loadingDirectory && state.collections.isEmpty()) items(columns, key = { "folder-placeholder-$it" }) {
                    LibrarySettingsGroup { Spacer(Modifier.fillMaxWidth().aspectRatio(1f)) }
                }
                items(state.collections, key = { "${it.identity.accountId}:${it.identity.remoteId}" }) { folder ->
                    LaunchedEffect(folder.identity, state.directoryArtworkRevision) {
                        model.loadCollectionArtwork(folder, state.directoryArtworkRevision)
                    }
                    Box(Modifier.animateItem(fadeInSpec = musicMotion(300), placementSpec = null, fadeOutSpec = musicMotion(180))) {
                        SupplementalCollectionCard(folder, state.playingCollection == folder.identity.remoteId,
                            state.artworkVersions[folder.identity], Modifier,
                            { model.selectCollection(folder) }, { model.playCollection(folder, storedPlaylistSort(context, folder.asSupplementalPlaylist(), state.account?.id)); Unit }, showPlay = model.canPlay,
                            openEnabled = model.pageAvailable(com.musicone.source.sdk.SourceSupplementalPage.COLLECTION))
                    }
                }
                if (state.directoryCursor != null) item("more", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { model.loadCollections(more = true) }, enabled = !state.loadingDirectory) { Text("加载更多收藏夹") }
                }
                state.directoryError?.let { error -> item("directory-error", span = { GridItemSpan(maxLineSpan) }) {
                    Column { MainSourceInlineMessage(error); TextButton(onClick = { model.loadCollections(state.directoryRetryMore) }) { Text("重试") } }
                } }
                if (state.collections.isEmpty() && !state.loadingDirectory && state.directoryError == null) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                    MainSourceInlineMessage("暂无收藏夹，可通过链接导入内容。")
                }
            }
        }
    }
}

@Composable
internal fun SupplementalLinkEntry(state: SupplementalState, model: SupplementalSourceModel) {
    SupplementalLinkInput(state.input, state.resolving, model::input, model::resolveInput)
}
