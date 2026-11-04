package com.musicone.sourceintegration

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.musicone.*
import com.musicone.source.sdk.*
import com.musicone.source.runtime.SourceHostCallbackGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext

internal data class SourceDestination(val name: String, val arguments: Map<String, String>)
internal class SourceUiCall(val request: SourceUiRequest, val result: CompletableDeferred<SourceUiResult>)

/** 音源服务先于页面建立，页面重建只更换播放与导航接收者。 */
internal class SourceApplicationHost private constructor(private val context: Context) : SourceHost, SourceUiHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val mutablePlayer = MutableStateFlow(SourcePlayerState())
    override val playerState = mutablePlayer.asStateFlow()
    override val reduceMotion get() = ExperiencePreferences.options.reduceMotion
    override val library = SourceLibraryService(context)
    override val cache: SourceCacheHost = SourceCacheService(context)
    override val ui: SourceUiHost get() = this
    override val contentUi = SourceContentUiBridge(context)
    val destination = MutableStateFlow<SourceDestination?>(null)
    val exitTargets = SourceExitTargets()
    val pendingUi = MutableStateFlow<SourceUiCall?>(null)
    val accountRevision = MutableStateFlow(0L)
    private val uiMutex = Mutex()
    private var playback: MusicPlaybackRuntime? = null
    private var playerJob: Job? = null

    fun start() {
        SourcePackageController.get(context).apply {
            bind(this@SourceApplicationHost, { playerState.value.sourceId }, {
                playerState.value.sourceId?.let { playback?.stopForSourceRemoval(it) }
            }, { id ->
                if (destination.value?.arguments?.get("sourceId") == id) destination.value = null
                accountRevision.value++
            })
            start()
        }
    }

    fun bind(playback: MusicPlaybackRuntime) {
        if (this.playback === playback) return
        playerJob?.cancel()
        this.playback = playback
        contentUi.pages.playback = playback
        playerJob = scope.launch { playback.state.collect { state ->
            mutablePlayer.value = SourcePlayerState(state.currentTrack?.source?.id, state.currentTrack?.id, state.isPlaying)
        } }
    }

    override fun play(tracks: List<SourceTrack>, startIndex: Int) = playDetailed(tracks.map {
        SourceTrackDetails(it, stableId = "${it.identity.sourceId}:${it.identity.remoteId}")
    }, startIndex)

    override fun playDetailed(tracks: List<SourceTrackDetails>, startIndex: Int) {
        require(tracks.isNotEmpty() && startIndex in tracks.indices) { "播放列表为空或位置无效" }
        val songs = tracks.map { it.toMusicTrack() }
        onMain { requirePlayback().playPlaylistTrack(libraryPlaylist("source-session", "播放队列", songs), songs[startIndex]) }
    }

    override fun enqueueDetailed(tracks: List<SourceTrackDetails>, playNext: Boolean) {
        val songs = tracks.map { it.toMusicTrack() }
        onMain { requirePlayback().enqueueTracks(songs, playNext) }
    }

    override fun playRecommendation(track: SourceTrackDetails) {
        val song = track.toMusicTrack()
        onMain { requirePlayback().playRecommendationTrack(song) }
    }

    override fun importTracks(tracks: List<SourceTrack>, destinationId: String) {
        val guard = SourceHostCallbackGuard.capture()
        scope.launch(guard?.context ?: EmptyCoroutineContext) {
            if (guard?.isCurrent() == false) return@launch
            try { library.importTracks(tracks.map { SourceTrackDetails(it, stableId = "${it.identity.sourceId}:${it.identity.remoteId}") }, destinationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportError(error.message ?: "导入失败") }
        }
    }
    override fun exitSource() {
        val sourceId = SourceHostCallbackGuard.capture()?.sourceId
        onMain {
            val route = destination.value
            if (route != null && (sourceId == null || route.arguments["sourceId"] == sourceId)) destination.value = null
            else exitTargets.exit(sourceId)
            accountRevision.value++
        }
    }
    override fun reportError(message: String) = onMain { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    override fun openDestination(destination: String, arguments: Map<String, String>) = onMain {
        if (destination == "source-radio") requirePlayback().startSourceRadio(requireNotNull(arguments["sourceId"]))
        else this.destination.value = SourceDestination(destination, arguments)
    }
    override suspend fun request(request: SourceUiRequest): SourceUiResult = uiMutex.withLock {
        val call = SourceUiCall(request, CompletableDeferred(currentCoroutineContext()[Job]))
        pendingUi.value = call
        try { call.result.await() } finally { if (pendingUi.value === call) pendingUi.value = null }
    }
    private fun requirePlayback() = checkNotNull(playback) { "播放服务尚未就绪" }
    private fun onMain(action: () -> Unit) {
        val guard = SourceHostCallbackGuard.capture()
        val guarded = { if (guard?.isCurrent() != false) action() }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded() else handler.post(guarded)
    }

    companion object {
        @Volatile private var instance: SourceApplicationHost? = null
        fun get(context: Context): SourceApplicationHost = instance ?: synchronized(this) {
            instance ?: SourceApplicationHost(context.applicationContext).also { instance = it }
        }
    }
}
