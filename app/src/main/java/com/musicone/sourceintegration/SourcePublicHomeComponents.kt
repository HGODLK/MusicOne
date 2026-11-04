package com.musicone.sourceintegration

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.musicone.*
import com.musicone.source.sdk.*

internal class SourcePublicHomeComponents(private val access: SourcePublicComponentAccess) : SourceHomeComponents, SourceLoadedHomeComponents {
    @Composable override fun SimilarRecommendations(pages: List<SourceSimilarRecommendation>, baseTrack: SourceTrackDetails?,
        loading: Boolean, player: SourcePlayerState, modifier: Modifier, onClick: (SourceTrackDetails) -> Unit, onRefresh: () -> Unit) {
        access.check()
        baseTrack?.let(access::check)
        pages.forEach { access.check(it.baseTrack); access.check(it.tracks) }
        val byId = pages.flatMap { it.tracks }.associateBy { it.toMusicTrack().id }
        val click = access.valueEvent<SourceTrackDetails>(onClick)
        access.Host {
            MainSourceSimilarRecommendationSection(MainSourceSimilarRecommendationUiState(baseTrack?.toMusicTrack(),
                pages.map { MainSourceSimilarRecommendation(it.baseTrack.toMusicTrack(), it.title, it.tracks.map { song -> MainSourceSimilarSong(song.toMusicTrack()) }) }, loading),
                player.stableTrackId, player.isPlaying, { byId[it.id]?.let(click) }, access.event(onRefresh), modifier)
        }
    }
    private fun song(track: SourceTrackDetails, title: String = ""): MainSourceHomeCard.Song {
        access.check(track)
        return MainSourceHomeCard.Song(track.toMusicTrack(), title)
    }

    @Composable override fun SongCard(track: SourceTrackDetails, title: String, current: Boolean,
        playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val card = song(track, title)
        access.Host { Box(modifier) { MainSourceHomeSongCard(card, current, playing, access.event(onClick)) } }
    }

    @Composable override fun SongGroup(title: String, tracks: List<SourceTrackDetails>, player: SourcePlayerState,
        modifier: Modifier, onClick: (SourceTrackDetails) -> Unit) {
        access.check(tracks)
        val byId = tracks.associateBy { it.toMusicTrack().id }
        access.Host {
            MainSourceHomeSongGroup(MainSourceHomeCard.SongGroup(title, tracks.map { it.toMusicTrack() }),
                player.stableTrackId, player.isPlaying, access.valueEvent { song: MusicTrack -> byId[song.id]?.let(onClick) }, modifier)
        }
    }

    @Composable override fun SongShelf(id: String, title: String, pages: List<List<SourceTrackDetails>>,
        player: SourcePlayerState, showFavorites: Boolean, modifier: Modifier, onClick: (SourceTrackDetails) -> Unit) {
        pages.forEach(access::check)
        val byId = pages.flatten().associateBy { it.toMusicTrack().id }
        access.Host {
            MainSourceHomeSongShelf(MainSourceHomeCard.SongShelf(id, title, pages.map { page -> page.map { song(it) } }),
                player.stableTrackId, player.isPlaying, access.valueEvent { song: MusicTrack -> byId[song.id]?.let(onClick) }, showFavorites, modifier)
        }
    }

    @Composable override fun DailyCard(collection: SourceCollection, date: String?, loading: Boolean,
        message: String?, modifier: Modifier, onClick: () -> Unit) {
        access.check(collection)
        access.Host { MainSourceDailyCard(collection.toMusicPlaylist(), date, loading, message, access.event(onClick), modifier) }
    }

    @Composable override fun DailyCard(collection: SourceCollection, tracks: List<SourceTrackDetails>,
        date: String?, loading: Boolean, message: String?, modifier: Modifier, onClick: () -> Unit) {
        access.check(collection); access.check(tracks)
        val playlist = loadedHomePlaylist(collection, tracks, access.sourceId, access.accountId())
        access.Host { MainSourceDailyCard(playlist, date, loading, message, access.event(onClick), modifier) }
    }

    @Composable override fun PersonalizedCards(collection: SourceCollection, tracks: List<SourceTrackDetails>,
        date: String?, loading: Boolean, message: String?, radioActive: Boolean, radioPlaying: Boolean,
        radioLoading: Boolean, radioVisible: Boolean, modifier: Modifier,
        onDailyClick: () -> Unit, onRadioClick: () -> Unit) {
        access.check(collection); access.check(tracks)
        val playlist = loadedHomePlaylist(collection, tracks, access.sourceId, access.accountId())
        access.Host { MainSourcePersonalizedCards(playlist, date, loading, message,
            radioActive, radioPlaying, radioLoading, access.event(onDailyClick), access.event(onRadioClick), radioVisible, modifier) }
    }

    @Composable override fun RadioCard(active: Boolean, playing: Boolean, loading: Boolean,
        visible: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
        access.Host { MainSourceRadioCard(active, playing, loading, visible, access.event(onClick), modifier, label) }
    }

    @Composable override fun PersonalizedCards(collection: SourceCollection, date: String?, loading: Boolean,
        message: String?, radioActive: Boolean, radioPlaying: Boolean, radioLoading: Boolean, radioVisible: Boolean,
        modifier: Modifier, onDailyClick: () -> Unit, onRadioClick: () -> Unit) {
        access.check(collection)
        access.Host { MainSourcePersonalizedCards(collection.toMusicPlaylist(), date, loading, message,
            radioActive, radioPlaying, radioLoading, access.event(onDailyClick), access.event(onRadioClick), radioVisible, modifier) }
    }

    @Composable override fun RecentPlay(snapshot: SourceRecentSnapshot, signedIn: Boolean, loading: Boolean,
        message: String?, onOpen: (SourceCollection) -> Unit) {
        access.check(snapshot.tracks)
        val collections = snapshot.collections + snapshot.albums + snapshot.radios
        collections.forEach(access::check)
        access.Host {
            MainSourceRecentPlaySection(MainSourceRecentPlayUiState(MainSourceRecentSnapshot(
                snapshot.tracks.map { it.toMusicTrack() }, snapshot.totalTracks, collections.map { it.toMusicPlaylist() },
                MusicSource.fromId(access.sourceId)), loading, message), signedIn,
                access.valueEvent { playlist: MusicPlaylist -> onOpen(playlist.toSourceCollection()) })
        }
    }
}
