package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.*
import com.musicone.source.sdk.*

/** 给音源包暴露的首页横滑货架组件，内部直接复用模板原实现。 */
internal class SourcePublicHomeShelfComponents(private val access: SourcePublicComponentAccess) : SourceHomeShelfComponents {
    @Composable override fun SongShelf(id: String, title: String, pages: List<List<SourceTrackDetails>>,
        player: SourcePlayerState, showFavorites: Boolean, edgeInsetDp: Float, modifier: Modifier,
        onClick: (SourceTrackDetails) -> Unit) {
        pages.forEach(access::check)
        val byId = pages.flatten().associateBy { it.toMusicTrack().id }
        val edgeInset = edgeInsetDp.dp
        access.Host {
            MainSourceHomeSongShelf(
                MainSourceHomeCard.SongShelf(id, title, pages.map { page ->
                    page.map { MainSourceHomeCard.Song(it.toMusicTrack(), "") }
                }),
                player.stableTrackId, player.isPlaying,
                access.valueEvent { song: MusicTrack -> byId[song.id]?.let(onClick) }.asHomeTrackPageClick(), showFavorites,
                modifier.mainSourceRecommendationEdgeLayout(edgeInset), edgeInset,
            )
        }
    }

    @Composable override fun CollectionShelf(collections: List<SourceCollection>, edgeInsetDp: Float,
        onPlay: (SourceCollection) -> Unit, onOpen: (SourceCollection) -> Unit) {
        collections.forEach(access::check)
        val byId = collections.associateBy { it.toMusicPlaylist().id }
        access.Host {
            PlaylistRecommendations(collections.map { it.toMusicPlaylist() }, edgeInsetDp.dp,
                access.valueEvent { playlist: MusicPlaylist -> byId[playlist.id]?.let(onOpen) },
                access.valueEvent { playlist: MusicPlaylist -> byId[playlist.id]?.let(onPlay) })
        }
    }
}
