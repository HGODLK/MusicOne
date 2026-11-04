package com.musicone.source.kugou

import androidx.compose.runtime.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

internal enum class MusicOnePage { HOME, MY, SEARCH, PLAYLIST, LOGIN }
internal data class MusicOneUiState(val page: MusicOnePage = MusicOnePage.HOME, val currentTrack: MusicTrack? = null,
    val isPlaying: Boolean = false, val playbackMessage: String? = null)
internal data class MusicCatalogUiState(val recommendedTracks: List<MusicTrack> = emptyList(), val recommendations: List<MusicPlaylist> = emptyList(),
    val loadingRecommendedTracks: Boolean = false, val loadingRecommendations: Boolean = false,
    val recommendedTracksMessage: String? = null, val recommendationMessage: String? = null, val actionMessage: String? = null)
internal data class KugouLibraryUiState(val signedIn: Boolean = false, val favoritePlaylist: MusicPlaylist? = null,
    val createdPlaylists: List<MusicPlaylist> = emptyList(), val collectedPlaylists: List<MusicPlaylist> = emptyList(),
    val loading: Boolean = false, val message: String? = null)
internal data class KugouFavoriteUiState(val ids: Set<String> = emptySet(), val message: String? = null)
internal class KugouFavoriteActions(val state: KugouFavoriteUiState, val toggle: (MusicTrack) -> Unit)
internal val LocalMusicFavorites = staticCompositionLocalOf<KugouFavoriteActions> { error("缺少酷狗收藏操作") }
internal val LocalKugouTrackMenu = staticCompositionLocalOf<(MusicTrack) -> Unit> { error("缺少酷狗歌曲菜单") }
internal class MusicPlaybackRuntime(val playTrack: (MusicTrack) -> Unit, val playPlaylist: (MusicPlaylist) -> Unit)
internal class PlatformHomeActions(val onRefreshRecommendedTracks: (Boolean) -> Unit,
    val onOpenLoadedPlaylist: (MusicPlaylist) -> Unit, val onOpenRecommendation: (MusicPlaylist) -> Unit,
    val onPlayRecommendation: (MusicPlaylist) -> Unit)
internal fun kugouLibraryColumns(width: Dp): Int = when { width.value >= 1000 -> 4; width.value >= 600 -> 3; else -> 2 }
internal val PlayerEnterAnimation = tween<Float>(480, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
internal val PlayerReturnAnimation = tween<Float>(360, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
