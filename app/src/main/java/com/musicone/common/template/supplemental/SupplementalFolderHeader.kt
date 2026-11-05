package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.SourceRemoteCollection

@Composable
internal fun SupplementalFolderHeader(folder: SourceRemoteCollection, state: SupplementalState, model: SupplementalSourceModel,
    sort: PlaylistSortSelection) {
    val snapshot = LocalMusicLibraryUi.current.snapshot
    val subscription = snapshot.subscriptions.firstOrNull {
        it.sourceId == model.sourceId && it.accountId == model.subscriptionAccountId && it.remoteCollectionId == folder.identity.remoteId
    }
    SupplementalCollectionHeader(folder, model.entry.descriptor.name, state.playingCollection == folder.identity.remoteId,
        state.subscribing, subscription != null, state.playingCollection == null && model.canPlay, model.entry.subscriptions != null && (model.pageProvider == null || model.subscriptionAccountId != null),
        { model.playCollection(folder, sort.value); Unit }, { model.subscribe(folder); Unit },
        { if (subscription != null) model.subscribe(folder, subscription); Unit },
        { if (subscription != null) model.removeSubscription(subscription); Unit }, showPlay = model.canPlay,
        sortAction = { MainSourcePlaylistSortButton(sort.value, sort.select) })
}
