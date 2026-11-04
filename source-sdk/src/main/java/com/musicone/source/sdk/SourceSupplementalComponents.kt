package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 补充模板的独立控件。解析、分页、导入和订阅由音源通过公开事件处理。 */
interface SourceSupplementalComponents {
    @Composable fun LinkInput(value: String, resolving: Boolean = false, onChange: (String) -> Unit, onResolve: () -> Unit)
    @Composable fun ContentSummary(content: SourceContent)
    @Composable fun ChildSelection(children: List<SourceChildItem>, selected: Set<TrackIdentity>, enabled: Boolean = true,
        onToggle: (TrackIdentity) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit)
    @Composable fun ImportTarget(destinations: List<SourceLocalCollection>, selectedId: String?, onSelect: (SourceLocalCollection) -> Unit)
    @Composable fun ImportActions(selectedCount: Int, hasChildren: Boolean, busy: Boolean = false,
        canImport: Boolean = true, canPlay: Boolean = true, onImport: () -> Unit, onPlay: () -> Unit)
    @Composable fun SupplementalAccount(account: SourceAccount?, sourceName: String, onManage: () -> Unit)
    @Composable fun RemoteCollectionCard(collection: SourceRemoteCollection, loading: Boolean = false,
        artworkRevision: Long? = null, modifier: Modifier = Modifier, onOpen: () -> Unit, onPlay: () -> Unit)
    @Composable fun RemoteCollectionRow(collection: SourceRemoteCollection, onOpen: () -> Unit)
    @Composable fun RemoteCollectionHeader(collection: SourceRemoteCollection, sourceName: String,
        playing: Boolean = false, subscribing: Boolean = false, subscribed: Boolean = false,
        canPlay: Boolean = true, canSubscribe: Boolean = false, onPlay: () -> Unit,
        onSubscribe: () -> Unit = {}, onRefreshSubscription: () -> Unit = {}, onRemoveSubscription: () -> Unit = {})
}
