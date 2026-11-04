package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.musicone.source.runtime.SourceInstance
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toSourceTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*

internal data class TrackThemeIdentity(val sourceId: String, val stableId: String,
    val accountId: String?, val remoteId: String)

internal fun MusicTrack.trackThemeIdentity() = TrackThemeIdentity(source.id, id, sourceAccountId, remoteId())

private data class TrackThemeOwner(val identity: TrackThemeIdentity?, val instance: SourceInstance?)

internal data class CurrentTrackTheme(val identity: TrackThemeIdentity? = null, val argb: Long? = null,
    val isCurrent: () -> Boolean = { false }, val ownerCurrent: () -> Boolean = { false },
    val platformColor: (String) -> Long? = { null })

/** 平台展示色不随歌曲账号变化；不同音源仍严格隔离。 */
internal fun TrackThemeIdentity?.sharesThemeOwner(other: TrackThemeIdentity?): Boolean =
    this != null && other != null && sourceId == other.sourceId

internal val LocalCurrentTrackTheme = staticCompositionLocalOf { CurrentTrackTheme() }

/** 只有当前曲目解析一次；列表和队列只消费结果，不各自调用外包。 */
@Composable
internal fun CurrentTrackThemeHost(playback: MusicPlaybackRuntime, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val sources = remember(context) { SourcePackageController.get(context) }
    val colors = remember(sources) { mutableStateMapOf<String, Pair<SourceInstance, Long>>() }
    val platformColor: (String) -> Long? = remember(sources) {
        { id -> colors[id]?.takeIf { it.first === sources.running(id) }?.second }
    }
    var theme by remember { mutableStateOf(CurrentTrackTheme()) }
    LaunchedEffect(playback, sources) {
        val resolver = CurrentTrackThemeResolver()
        fun owner(): TrackThemeOwner {
            val track = playback.state.value.currentTrack
            val instance = track?.let { sources.running(it.source.id) }
            return TrackThemeOwner(track?.trackThemeIdentity(), instance)
        }
        try {
            combine(playback.state, sources.installed) { _, _ -> owner() }
                .distinctUntilChanged().collectLatest { request ->
                    colors.keys.toList().forEach { id ->
                        if (colors[id]?.first !== sources.running(id)) colors.remove(id)
                    }
                    val diagnosticTrack = PlayerMotionDiagnostics.track(request.identity)
                    PlayerMotionDiagnostics.event("theme-request") { "track=$diagnosticTrack instance=${request.instance != null}" }
                    val track = playback.state.value.currentTrack
                    val current = { owner() == request }
                    val ownerCurrent = {
                        val live = owner()
                        live.identity.sharesThemeOwner(request.identity) &&
                            live.instance === request.instance
                    }
                    val pendingArgb = request.identity?.sourceId?.let(platformColor)
                    resolver.resolve(track?.source == MusicSource.LOCAL, current, load = {
                        val instance = request.instance
                        if (track == null || instance == null) null
                        else withContext(Dispatchers.IO) {
                            instance.withOperation {
                                instance.entry.trackTheme?.colorArgb(track.toSourceTrack())
                            }
                        }
                    }, pendingArgb = pendingArgb, publish = {
                        PlayerMotionDiagnostics.event("theme-publish") { "track=$diagnosticTrack argb=$it current=${current()}" }
                        val id = request.identity?.sourceId
                        if (current() && id != null && request.instance != null) {
                            if (it != null) colors[id] = request.instance to it else colors.remove(id)
                        }
                        theme = CurrentTrackTheme(request.identity, it, current, ownerCurrent, platformColor)
                    })
                }
        } finally { resolver.invalidate() }
    }
    CompositionLocalProvider(LocalCurrentTrackTheme provides theme, content = content)
}
