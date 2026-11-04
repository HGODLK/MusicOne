package com.musicone.sourceintegration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicone.*
import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException

/** 最近播放与相似推荐沿用公共货架，打开后仍进入同一份完整歌单目的地。 */
@Composable
internal fun SourcePublicDiscoveryContent(request: SourcePageRequest, catalog: MusicCatalogUiState,
    player: MusicPlaybackRuntime, bottomInset: Dp, onPlaylist: (MusicPlaylist) -> Unit, onClose: () -> Unit) {
    val source = MusicSource.fromId(request.sourceId)
    val context = LocalContext.current
    val playback by player.state.collectAsStateWithLifecycle()
    val title = when (request) {
        is SourcePageRequest.DailyRecommendations -> "每日推荐"
        is SourcePageRequest.Recent -> "最近播放"
        else -> "相似推荐"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        SettingsHeader(title, onClose)
        Spacer(Modifier.height(24.dp))
        when (request) {
            is SourcePageRequest.DailyRecommendations -> {
                val tracks = catalog.recommendedTracks
                val playlist = remember(source, tracks, catalog.recommendedTracksDate) {
                    sourcePublicEphemeralPlaylist(source, "daily:${catalog.recommendedTracksDate.orEmpty()}", title, tracks, DAILY_PLAYLIST_PROVIDER_DATA)
                }
                LaunchedEffect(playlist.id, tracks, catalog.recommendedTracksSettled) {
                    if (tracks.isNotEmpty()) onPlaylist(playlist)
                }
                MainSourceInlineMessage(when {
                    catalog.loadingRecommendedTracks -> "正在加载每日推荐…"
                    catalog.recommendedTracksMessage != null -> catalog.recommendedTracksMessage
                    else -> "暂时没有每日推荐"
                })
                val model: MusicCatalogViewModel = viewModel()
                TextButton(onClick = { model.refreshRecommendedTracks(true) }) { Text("刷新") }
            }
            is SourcePageRequest.Recent -> {
                val model: MainSourceRecentPlayViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                ObserveMainSourceRecentPlayVisibility(model, true)
                LaunchedEffect(source) { model.configure(source); model.ensureLoaded() }
                val signedIn = !source.requiresAccount || PlatformPreferences(context).readSession(source).account != null
                MainSourceRecentPlaySection(state, signedIn, onPlaylist)
                TextButton(onClick = { model.ensureLoaded(true) }) { Text("刷新") }
            }
            is SourcePageRequest.Similar -> {
                var revision by remember { mutableIntStateOf(0) }
                var state by remember(request) { mutableStateOf(MainSourceSimilarRecommendationUiState(loading = true)) }
                var error by remember(request) { mutableStateOf<String?>(null) }
                LaunchedEffect(request, revision) {
                    val base = request.track.toMusicTrack()
                    state = state.copy(baseTrack = base, loading = true)
                    try {
                        val service = requireNotNull(SourcePackageController.get(context).running(source.id)?.entry?.discovery)
                        val section = service.similarSection(request.track, SourceCursorRequest(
                            policy = if (revision == 0) SourceFetchPolicy.CACHE_FIRST else SourceFetchPolicy.REFRESH))
                        state = MainSourceSimilarRecommendationUiState(base,
                            listOf(MainSourceSimilarRecommendation(base, section.title,
                                section.tracks.map { MainSourceSimilarSong(it.toMusicTrack()) })))
                        error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.asUserMessage(); state = state.copy(loading = false) }
                }
                error?.let { MainSourceInlineMessage(it) }
                MainSourceSimilarRecommendationSection(state, playback.currentTrack?.id, playback.isPlaying,
                    player::playTrackNext, { revision++ })
            }
            else -> Unit
        }
        Spacer(Modifier.height(bottomInset))
    }
}

private fun sourcePublicEphemeralPlaylist(source: MusicSource, key: String, title: String,
    tracks: List<MusicTrack>, providerData: Map<String, String> = mapOf("templateEphemeral" to "true")): MusicPlaylist {
    val colors = mainSourceArtworkColors(title)
    return MusicPlaylist("${source.id}:$key", source, title, "${tracks.size} 首", "", tracks.size,
        colors.first, colors.second, title.take(1), tracks, tracks.firstOrNull()?.artworkUrl,
        providerData = providerData)
}

/** 认证内容来自请求音源，宿主只复用现有设置页壳与返回协议。 */
@Composable
internal fun SourcePublicAuthentication(sourceId: String, bottomInset: Dp, onClose: () -> Unit,
    mode: String = "authentication", interactive: Boolean = true) {
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val instance = remember(sourceId, installed) { controller.running(sourceId) }
    val back = {
        if (instance == null || !runCatching { instance.onBack() }.getOrDefault(false)) onClose()
    }
    BackHandler(enabled = interactive, onBack = back)
    Column(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
        Box(Modifier.padding(20.dp)) { SettingsHeader(instance?.entry?.descriptor?.name ?: "音源不可用", back) }
        if (instance != null) SourceExternalSurface(instance, SourceRoute(), Modifier.weight(1f),
            mode = mode, onExit = onClose, interactive = interactive)
    }
}
