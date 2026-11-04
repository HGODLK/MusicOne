package com.musicone.sourceintegration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.*
import com.musicone.source.sdk.*

internal class SourcePublicLibraryComponents(private val access: SourcePublicComponentAccess) : SourceLibraryComponents {
    private val legacy = SourceContentUiBridge(access.context)

    @Composable override fun CollectionCard(collection: SourceCollection, modifier: Modifier, onClick: () -> Unit) {
        access.check(collection); legacy.CollectionCard(collection, modifier, access.event(onClick))
    }
    @Composable override fun CollectionRow(collection: SourceCollection, modifier: Modifier, onClick: () -> Unit) {
        access.check(collection)
        access.Host { Box(modifier) { MainSourceLibraryPlaylistRow(collection.toMusicPlaylist(), 0L, access.event(onClick)) } }
    }
    @Composable override fun RecommendationCard(collection: SourceCollection, widthDp: Float, modifier: Modifier,
        onPlay: () -> Unit, onClick: () -> Unit) {
        access.check(collection); legacy.RecommendationCollectionCard(collection, modifier, widthDp, access.event(onPlay), access.event(onClick))
    }
    @Composable override fun FeaturedCard(collection: SourceCollection, heightDp: Float, compact: Boolean,
        modifier: Modifier, onPlay: () -> Unit, onClick: () -> Unit) {
        access.check(collection); legacy.FeaturedCollectionCard(collection, modifier, heightDp, compact, access.event(onPlay), access.event(onClick))
    }
    @Composable override fun CollectionShelf(collections: List<SourceCollection>, onPlay: (SourceCollection) -> Unit, onOpen: (SourceCollection) -> Unit) {
        collections.forEach(access::check)
        val byId = collections.associateBy { it.toMusicPlaylist().id }
        access.Host { PlaylistRecommendations(collections.map { it.toMusicPlaylist() },
            access.valueEvent { song: MusicPlaylist -> byId[song.id]?.let(onOpen) },
            access.valueEvent { song: MusicPlaylist -> byId[song.id]?.let(onPlay) }) }
    }
    @Composable override fun ProfileHeader(account: SourceAccount?, onLogin: () -> Unit) {
        access.check(); legacy.ProfileHeader(access.sourceId, account, access.event(onLogin))
    }
    @Composable override fun FavoritesRow(collection: SourceCollection?, signedIn: Boolean, onClick: () -> Unit) {
        collection?.let(access::check); access.check(); legacy.FavoritesRow(collection, signedIn, access.event(onClick))
    }
    @Composable override fun CollectionSegment(created: Boolean, createdCount: Int, collectedCount: Int,
        onCreated: () -> Unit, onCollected: () -> Unit) {
        access.check(); legacy.CollectionSegment(created, createdCount, collectedCount, access.event(onCreated), access.event(onCollected))
    }
    @Composable override fun CollectionButton(saved: Boolean, updating: Boolean, onClick: () -> Unit) {
        access.Host { PlaylistCollectionButton(saved, updating, access.event(onClick)) }
    }
    @Composable override fun EntityRow(entity: SourceEntity, onClick: () -> Unit) {
        access.Host { SearchEntityRow(entity.reference.name, entity.description, entity.artwork.url, entity.reference.id,
            entity.reference.kind == "artist", onClick = access.event(onClick)) }
    }
    @Composable override fun EntitySummary(entity: SourceEntity, playable: Boolean, onPlay: () -> Unit) {
        access.Host {
            if (entity.reference.kind == "artist") {
                val photos = listOfNotNull(entity.artwork.url)
                val pager = rememberPagerState(pageCount = { photos.size.coerceAtLeast(1) })
                val appearance = rememberArtistAppearance(photos, pager)
                ArtistIntroduction(MainSourceArtistProfile(entity.reference.name, entity.artwork.url, entity.description, photos),
                    Modifier.fillMaxWidth().height(380.dp), playable, access.event(onPlay), pager, appearance,
                    usePhotoTheme = false)
            } else {
                PlaylistIntroduction(entity.asTemplatePlaylist(MusicSource.fromId(access.sourceId)),
                    PlaylistSort.ADDED_DESC, access.event(onPlay), access.event(onPlay), {}, showPlaybackActions = playable)
            }
        }
    }
    @Composable override fun SectionTitle(title: String) { access.Host { SearchGroupTitle(title) } }
    @Composable override fun Empty(message: String, modifier: Modifier) { access.Host { EmptyState(message, modifier) } }
    @Composable override fun InlineMessage(message: String, modifier: Modifier) { access.Host { MainSourceInlineMessage(message, modifier) } }
    @Composable override fun Loading() { access.Host { MainSourceHomeLoadingShelf() } }
    @Composable override fun LoadMore(loading: Boolean, error: String?, hasMore: Boolean, onLoad: () -> Unit) {
        access.Host { ArtistLoadMore(loading, error, hasMore, access.event(onLoad)) }
    }
}
