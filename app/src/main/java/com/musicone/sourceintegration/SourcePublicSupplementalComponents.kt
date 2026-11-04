package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.musicone.*
import com.musicone.source.sdk.*

/** 导入与订阅仍由调用方处理，公共控件只传递公开身份和用户事件。 */
internal class SourcePublicSupplementalComponents(private val access: SourcePublicComponentAccess) : SourceSupplementalComponents {
    @Composable override fun LinkInput(value: String, resolving: Boolean, onChange: (String) -> Unit, onResolve: () -> Unit) {
        access.Host { SupplementalLinkInput(value, resolving, access.valueEvent(onChange), access.event(onResolve)) }
    }
    @Composable override fun ContentSummary(content: SourceContent) {
        access.check(content.identity)
        content.playableTrack?.let(access::check)
        access.Host { SupplementalContentSummary(content) }
    }
    @Composable override fun ChildSelection(children: List<SourceChildItem>, selected: Set<TrackIdentity>, enabled: Boolean,
        onToggle: (TrackIdentity) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit) {
        children.forEach { access.check(it.identity) }; selected.forEach(access::check)
        val toggle = access.valueEvent<TrackIdentity> { access.check(it); onToggle(it) }
        access.Host { SupplementalChildSelection(children, selected, enabled,
            toggle, access.event(onSelectAll), access.event(onClear)) }
    }
    @Composable override fun ImportTarget(destinations: List<SourceLocalCollection>, selectedId: String?, onSelect: (SourceLocalCollection) -> Unit) {
        access.Host { SupplementalImportDestination(destinations, selectedId, access.valueEvent(onSelect)) }
    }
    @Composable override fun ImportActions(selectedCount: Int, hasChildren: Boolean, busy: Boolean, canImport: Boolean,
        canPlay: Boolean, onImport: () -> Unit, onPlay: () -> Unit) {
        access.Host { SupplementalImportActions(selectedCount, hasChildren, busy, canImport, canPlay,
            access.event(onImport), access.event(onPlay)) }
    }
    @Composable override fun SupplementalAccount(account: SourceAccount?, sourceName: String, onManage: () -> Unit) {
        access.Host { SupplementalAccountHeader(account, sourceName, access.event(onManage)) }
    }
    @Composable override fun RemoteCollectionCard(collection: SourceRemoteCollection, loading: Boolean, artworkRevision: Long?,
        modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
        access.check(collection.identity)
        access.Host { SupplementalCollectionCard(collection, loading, artworkRevision, modifier, access.event(onOpen), access.event(onPlay)) }
    }
    @Composable override fun RemoteCollectionRow(collection: SourceRemoteCollection, onOpen: () -> Unit) {
        access.check(collection.identity)
        access.Host { MainSourceLibraryPlaylistRow(collection.asSupplementalPlaylist(), 0L, access.event(onOpen)) }
    }
    @Composable override fun RemoteCollectionHeader(collection: SourceRemoteCollection, sourceName: String, playing: Boolean,
        subscribing: Boolean, subscribed: Boolean, canPlay: Boolean, canSubscribe: Boolean, onPlay: () -> Unit,
        onSubscribe: () -> Unit, onRefreshSubscription: () -> Unit, onRemoveSubscription: () -> Unit) {
        access.check(collection.identity)
        access.Host { SupplementalCollectionHeader(collection, sourceName, playing, subscribing, subscribed, canPlay, canSubscribe,
            access.event(onPlay), access.event(onSubscribe), access.event(onRefreshSubscription), access.event(onRemoveSubscription)) }
    }
}
