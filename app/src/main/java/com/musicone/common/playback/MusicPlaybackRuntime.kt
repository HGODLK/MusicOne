package com.musicone

import android.app.Application
import com.musicone.sourceintegration.*
import com.musicone.source.sdk.SourcePlaybackSessionEndReason
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MusicPlaybackRuntime(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val playlistLibrary = PlaylistLibrary(application)
    private val playbackResolver = PlatformPlaybackResolver(application)
    private val nextAudioPreloader = PlaybackPreloader(application, scope)
    private val qualityPreferences = PlaybackQualityPreferences(application)
    private val qualityCache = PlaybackQualityCache(application)
    internal var serviceConnectionRequester: (() -> Unit)? = null
    internal fun ensureServiceConnection() { serviceConnectionRequester?.invoke() }
    private val playbackSession = PlaybackSessionCoordinator(application, ::ensureServiceConnection)
    private val playbackHistory = PlaybackHistory()
    private val cachedLyrics = CachedLyricsStore(application)
    private val _state = MutableStateFlow(MusicOneUiState())
    val state: StateFlow<MusicOneUiState> = _state.asStateFlow()
    private val _progressMs = MutableStateFlow(0L)
    private val _playbackProgress = MutableStateFlow(PlaybackProgressSnapshot())
    internal val playbackProgress: StateFlow<PlaybackProgressSnapshot> = _playbackProgress.asStateFlow()
    private val _playbackActivity = MutableStateFlow(PlaybackActivity())
    internal val playbackActivity: StateFlow<PlaybackActivity> = _playbackActivity.asStateFlow()
    internal val seekPreview = PlayerSeekPreview()
    internal val rapidTrackSwitch = RapidTrackSwitch(
        scope = scope,
        cachedLyrics = { track ->
            playbackResolver.cachedOrLibraryLyrics(track)
        },
        neighbor = { anchor, next, first ->
            if (first) miniPlayerNeighbor(_state.value, playbackHistory, next)
                ?: _state.value.currentTrack?.takeIf { _state.value.queue.size == 1 }
            else rapidQueueNeighbor(_state.value, anchor, next)
        },
        commit = { track, direction ->
            playbackTrace("MusicOne:manual-commit") { commitRapidTrackSwitch(track, direction) }
        },
        direct = { forward ->
            playbackTrace("MusicOne:manual-direct") { if (forward) next() else previous() }
        },
        beginBrowsing = ::beginRapidTrackBrowsing,
        waitForSingleLyrics = { _state.value.currentTrack?.source?.lyricBehavior?.waitForSingleSwitchSettlement == true },
    )
    internal val favoritePreparation = MusicFavoritePreparationTargets()
    private var playbackRequestGeneration = 0L
    private var playWhenReadyRequested = false
    private val failureRecovery = PlaybackFailureRecovery()
    private val playbackPresentation = PlaybackPresentationDelay(
        scope = scope,
        stillStopped = { key ->
            isCurrentRequest(key.generation, key.trackId) && playbackSession.player?.isPlaying != true
        },
        publish = { key, playing ->
            if (isCurrentRequest(key.generation, key.trackId)) {
                _state.update { it.copy(isPlaying = playing) }
            }
        },
    )

    private val trackLoader = PlaybackTrackLoader(scope, nextAudioPreloader::take,
        playbackResolver::resolve, playbackResolver::enrichMetadata, ::isCurrentRequest,
        { playbackSession.player?.isPlaying == true }, ::onTrackEvent, PlaybackResolutionWakeGuard(application), playbackResolver::recover,
        sourceStatus = { queueSourceStatus(it.source) }, selectQuality = { track ->
            val preferred = qualityPreferences.read(track.source)
            preferred to PlaybackOptions.effective(application, preferred, track.source).playbackEquivalent()
        })
    private val lyricsLoader = PlaybackLyricsLoader(scope, playbackResolver::lyrics,
        ::isCurrentRequest, ::onLyricsUpdate)
    private val qualityController = PlaybackQualityController(
        scope, playbackResolver::availableQualities, playbackResolver::resolveExact,
        playbackResolver::resolvePreferred, qualityPreferences::save,
        { source -> PlaybackOptions.effective(application, qualityPreferences.read(source), source).playbackEquivalent() },
        ::isCurrentRequest, { playWhenReadyRequested && playbackSession.player?.isPlaying != true },
        ::onQualityEvent,
    )
    private var radioSource = MusicSource.QQ
    private val radioRepository = SourceRadioRepository(application) { radioSource }
    private val radioController = SourceRadioController(scope, radioRepository::load,
        { SourceRadioQueueSnapshot(_state.value.queue, _state.value.currentTrack) }, ::onRadioEvent)
    private val recentPlayReporter = SourceRecentPlayReporter(application, scope)
    private val playedDuration = PlaybackPlayedDuration()
    private val sourcePlaybackSessions = SourcePlaybackSessionReporter(application)
    private val queueRemoval = PlaybackQueueRemoval(_state,
        startNext = { track, remaining ->
            failureRecovery.reset()
            startTrack(track, remaining.queue, playWhenReady = playWhenReadyRequested,
                preserveRadio = remaining.radioActive)
            if (remaining.radioActive) prefetchRadioIfNeeded()
        },
        clearPlayback = ::clearRemovedQueue,
        save = { playbackSession.save(_state.value, currentPlaybackPosition()) })

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (!playerMatchesCurrentTrack()) return
            val track = _state.value.currentTrack ?: return
            sourcePlaybackSessions.pause(currentPlaybackPosition())
            if (isUsbOutputFailure(error, PlaybackOptions.usbLosslessOutput)) {
                pauseForUsbOutputLoss()
                return
            }
            if (_state.value.activeQuality?.capabilityQuality == AudioQuality.DOLBY || resolvedPlaybackRequiresDolby(track.previewUrl.orEmpty())) {
                restartWithoutDolby()
                return
            }
            val causeType = generateSequence<Throwable>(error) { it.cause }.last()::class.java.simpleName
            Log.w("MusicPlayback", "Media3 播放失败：errorCode=${error.errorCode}，cause=$causeType")
            val previous = SupplementalPlaybackRequests.resource(track.previewUrl)
            if (playWhenReadyRequested && failureRecovery.mayRenew(track)) {
                // 间隔两秒后从断点重新取源续播；期间切歌、暂停都会让这次续播失效。
                val generation = playbackRequestGeneration
                val position = currentPlaybackPosition()
                scope.launch {
                    delay(PLAYER_ERROR_RENEW_DELAY_MS)
                    if (generation != playbackRequestGeneration || !playWhenReadyRequested ||
                        _state.value.currentTrack?.id != track.id) return@launch
                    startTrack(track.copy(previewUrl = ""), _state.value.queue, position,
                        preserveRadio = _state.value.radioActive, recoverFrom = previous?.takeIf { it.sourceResource != null })
                }
                return
            }
            sourcePlaybackSessions.finish(SourcePlaybackSessionEndReason.ERROR, currentPlaybackPosition())
            // 续播次数用尽后统一跳过，不再按音源声明保留在失败歌曲上。
            if (recoverUnavailableTrack("播放失败")) return
            if (track.source.playbackBehavior?.pauseOnFailure == true) {
                playWhenReadyRequested = false
                playbackSession.whenReady(Player::pause)
            }
            _playbackActivity.value = PlaybackActivity(track.id, false)
            playbackPresentation.transportStopped(playbackPresentationKey(track))
            _state.update { it.copy(playbackMessage = "播放失败，请稍后重试") }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val affectsPresentation = events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) ||
                events.contains(Player.EVENT_PLAYER_ERROR)
            if (!affectsPresentation || !playerMatchesCurrentTrack()) return
            val track = _state.value.currentTrack ?: return
            // 同批旧停止回调可能晚于新媒体装载，统一读取批次结束时的真实状态。
            _playbackActivity.value = PlaybackActivity(track.id, player.isPlaying)
            playedDuration.record(playbackRequestGeneration, player.isPlaying)
            val position = currentPlaybackPosition()
            updateProgress(position, track.id)
            sourcePlaybackSessions.observe(track, player.isPlaying, position,
                completed = player.playbackState == Player.STATE_ENDED)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                PlaybackPipelineDiagnostics.stage(if (player.isPlaying) "开始播放" else "停止推进", playbackRequestGeneration)
                if (player.isPlaying) { failureRecovery.reset(); reportRecentPlay(track, playbackRequestGeneration) }
                else playbackSession.save(_state.value, position)
            }
            val key = playbackPresentationKey(track)
            playWhenReadyRequested = player.playWhenReady
            when {
                player.isPlaying -> {
                    lyricsLoader.onPlaybackStarted(PlaybackRequestKey(track.id, playbackRequestGeneration))
                    playbackPresentation.present(key, true)
                }
                !player.playWhenReady -> playbackPresentation.present(key, false)
                else -> playbackPresentation.transportStopped(key)
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED && playWhenReadyRequested && !PlaybackSleepTimer.consumeTrackEnd()) {
                PlaybackPipelineDiagnostics.stage("曲末切歌", playbackRequestGeneration)
                advance(automatic = true)
            }
        }
    }

    init {
        scope.observeDolbyPlaybackRestrictions { allowed ->
            val snapshot = _state.value
            if (!allowed && (snapshot.activeQuality?.capabilityQuality == AudioQuality.DOLBY ||
                    snapshot.qualityTarget?.capabilityQuality == AudioQuality.DOLBY ||
                    resolvedPlaybackRequiresDolby(snapshot.currentTrack?.previewUrl.orEmpty()))) {
                restartWithoutDolby()
            }
            refreshQualityOptions()
        }
        scope.observeVerbatimLyricsPreference(
            currentTrack = { _state.value.currentTrack?.takeIf {
                playerMatchesCurrentTrack() && rapidTrackSwitch.presentation.value == null
            } },
            refresh = { loadLyrics(it, playbackRequestGeneration) },
        )
        scope.launch {
            PlaybackAudioInfo.validation.collect { result ->
                if (result != null) _state.update { current ->
                    if (current.currentTrack?.previewUrl != result.first) current else {
                        val quality = if (result.second) resolvedPlaybackQuality(result.first) ?: current.activeQuality else null
                        current.copy(activeQuality = quality,
                            availableQualities = if (result.second) verifiedPlayerQualities(quality, current.availableQualities) else emptyList(),
                            playbackMessage = if (result.second) null else "当前音源为试听片段")
                    }
                }
            }
        }

        scope.launch {
            state.collect { current ->
                if (playerMatchesCurrentTrack()) playbackSession.remember(
                    current, playbackSession.player?.currentMediaItem?.mediaId,
                )
            }
        }
        scope.prefetchPlayerArtwork(state)
        nextAudioPreloader.observe({ _state.value }, { rapidTrackSwitch.presentation.value != null },
            { playbackSession.player }, qualityQueryWaiting = { qualityController.waitingForUser })
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (!_playbackActivity.value.advancing || !playerMatchesCurrentTrack()) continue
                val position = currentPlaybackPosition()
                updateProgress(position)
                playbackSession.saveProgressIfNeeded(_state.value, position)
                _state.value.currentTrack?.let {
                    reportRecentPlay(it, playbackRequestGeneration)
                    sourcePlaybackSessions.observe(it, true, position, progress = true)
                }
                prefetchRadioIfNeeded()
            }
        }
    }

    fun configureSource(source: MusicSource) {
        // 浏览入口切换不接管或替换当前混合队列，包括正在解析的曲目。
        if (_state.value.currentTrack != null) return
        // 页面重建时沿用服务当前请求，不能取消后台正在解析的下一首。
        if (_state.value.currentTrack?.source == source &&
            (playerMatchesCurrentTrack() || trackLoader.isResolving || rapidTrackSwitch.presentation.value != null)) return
        stopRadio()
        val restore = playbackSession.restoreStored(source, _state.value)
        if (restore == null && _state.value.currentTrack != null) return
        val generation = ++playbackRequestGeneration
        cancelPendingTrackWork()
        if (restore != null) {
            _state.value = restoreRadioQueue(restore.state)
            playWhenReadyRequested = _state.value.isPlaying
            updateProgress(restore.positionMs)
            _playbackActivity.value = PlaybackActivity(_state.value.currentTrack?.id, false)
        }
        playbackSession.whenReady { player ->
            if (generation != playbackRequestGeneration) return@whenReady
            val live = playbackSession.restoreLive(source, _state.value, generation, player)
            if (live != null) {
                _state.value = restoreRadioQueue(live.state)
                playWhenReadyRequested = live.playWhenReady
                updateProgress(live.positionMs, live.state.currentTrack?.id)
                _playbackActivity.value = PlaybackActivity(live.state.currentTrack?.id, live.playing)
            }
            val track = _state.value.currentTrack
            if (resolvedPlaybackRequiresDolby(track?.previewUrl.orEmpty()) && !DolbyPlaybackPolicy.eligibility.allowed) {
                restartWithoutDolby()
            } else track?.let { loadLyrics(it, generation) }
        }
    }

    private fun restoreRadioQueue(state: MusicOneUiState): MusicOneUiState {
        if (!state.radioActive) return state
        radioSource = state.currentTrack?.source ?: radioSource
        return state.copy(queue = radioController.restore(state.queue, state.currentTrack?.id),
            shuffle = false, repeatMode = RepeatMode.ALL)
    }

    fun setPage(page: MusicOnePage) {
        _state.update { it.copy(page = page) }
    }

    fun playTrack(track: MusicTrack) {
        if (rejectUnavailableQueueTrack(track)) return
        if (!track.playable && track.source == MusicSource.LOCAL) {
            _state.update { it.copy(playbackMessage = track.unavailableReason ?: "文件已失联，请重新关联") }
            return
        }
        failureRecovery.reset()
        val snapshot = _state.value
        if (snapshot.currentTrack?.id == track.id) {
            if (trackLoader.isResolving) return
            togglePlay()
            return
        }
        if (snapshot.radioActive && snapshot.queue.any { it.id == track.id }) {
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
            startTrackFromQueue(track)
            prefetchRadioIfNeeded()
            return
        }
        val existingQueue = snapshot.queue
        val queue = appendPlaybackTrack(existingQueue, track)
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        startTrack(track, queue)
    }

    fun removeTrackFromQueue(track: MusicTrack) = queueRemoval.remove(track)
    fun removeTracksFromQueue(tracks: List<MusicTrack>) = queueRemoval.removeAll(tracks)

    private fun rejectUnavailableQueueTrack(track: MusicTrack): Boolean {
        val status = queueSourceStatus(track.source)
        if (status == QueueSourceStatus.AVAILABLE || status == QueueSourceStatus.INITIALIZING) return false
        _state.update { it.copy(playbackMessage = status.message(track.source)) }
        return true
    }

    private fun stopQueueWithoutSource(current: MusicTrack) {
        stopForSourceRemoval(current.source.id)
        _state.update { it.withUnavailableQueueStopped() }
    }

    fun enqueueTracks(tracks: List<MusicTrack>, playNext: Boolean) {
        val selected = tracks.distinctBy { it.source.id to it.id }
        if (selected.isEmpty()) return
        if (playNext) {
            val wasRadio = _state.value.radioActive
            _state.update { snapshot -> selected.asReversed().fold(snapshot) { value, track -> value.withTrackInsertedNext(track) } }
            if (wasRadio && !_state.value.radioActive) stopRadio()
            playbackSession.save(_state.value, currentPlaybackPosition())
        }
        else {
            _state.update { snapshot ->
                val queue = selected.fold(snapshot.queue, ::appendPlaybackTrack)
                snapshot.copy(queue = queue, queueOrder = snapshot.queueOrder.reconcile(queue))
            }
            playbackSession.save(_state.value, currentPlaybackPosition())
        }
    }

    fun stopForSourceRemoval(sourceId: String) {
        nextAudioPreloader.stopForSource(sourceId)
        if (_state.value.currentTrack?.source?.id != sourceId) return
        val position = currentPlaybackPosition()
        sourcePlaybackSessions.finish(SourcePlaybackSessionEndReason.STOPPED, position)
        ++playbackRequestGeneration
        playWhenReadyRequested = false
        failureRecovery.reset()
        rapidTrackSwitch.cancel()
        cancelPendingTrackWork()
        playbackPresentation.cancel()
        stopRadio()
        seekPreview.clear()
        playbackSession.clearPendingSeek()
        playbackSession.player?.let { it.stop(); it.clearMediaItems() }
        _playbackActivity.value = PlaybackActivity()
        _state.update { it.copy(isPlaying = false, qualityLoading = false, qualityChanging = false) }
        playbackSession.save(_state.value, position)
    }

    private fun clearRemovedQueue() {
        sourcePlaybackSessions.finish(SourcePlaybackSessionEndReason.STOPPED)
        val generation = ++playbackRequestGeneration
        playWhenReadyRequested = false
        failureRecovery.reset()
        rapidTrackSwitch.cancel()
        nextAudioPreloader.stop()
        cancelPendingTrackWork()
        playbackPresentation.cancel()
        playbackHistory.clear()
        seekPreview.clear()
        stopRadio()
        _playbackActivity.value = PlaybackActivity()
        updateProgress(0L)
        playbackSession.clearQueue { playbackRequestGeneration == generation && _state.value.currentTrack == null }
    }

    fun insertTrackNext(track: MusicTrack) {
        if (_state.value.currentTrack == null) { playTrack(track); return }
        val wasRadio = _state.value.radioActive
        _state.update { it.withTrackInsertedNext(track) }
        if (wasRadio && !_state.value.radioActive) stopRadio()
        playbackSession.save(_state.value, currentPlaybackPosition())
    }

    fun playTrackNext(track: MusicTrack) {
        if (rejectUnavailableQueueTrack(track)) return
        failureRecovery.reset()
        val snapshot = _state.value
        if (snapshot.currentTrack?.let { it.source == track.source && it.id == track.id } == true) {
            playTrack(track)
            return
        }
        if (snapshot.radioActive && track.source == radioSource) {
            val queue = radioController.insert(snapshot.queue, snapshot.currentTrack, track)
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
            startTrack(track, queue, preserveRadio = true)
            prefetchRadioIfNeeded()
            return
        }
        val existingQueue = snapshot.queue
        val queue = insertTrackAfterCurrent(existingQueue, snapshot.currentTrack, track)
        _state.update { it.copy(queueOrder = it.queueOrder.insert(existingQueue, snapshot.currentTrack, track)) }
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        startTrack(track, queue)
    }

    fun playRecommendationTrack(track: MusicTrack) {
        if (rejectUnavailableQueueTrack(track)) return
        PlaybackOptions.initialize(application)
        val snapshot = _state.value
        when (homeRecommendationAction(
            trackSource = track.source,
            radioActive = snapshot.radioActive,
            radioSource = radioSource,
            hasQueue = snapshot.currentTrack != null || snapshot.queue.isNotEmpty(),
            keepQueue = PlaybackOptions.keepQueueOnHomeRecommendation,
            radioAvailable = radioRepository.available(track.source),
        )) {
            HomeRecommendationAction.JOIN_RADIO -> joinRadioWithTrack(track, snapshot)
            HomeRecommendationAction.INSERT_IN_QUEUE -> playTrackNext(track)
            HomeRecommendationAction.START_RADIO -> {
                radioSource = track.source
                stopRadio()
                playbackHistory.clear()
                radioController.startWithSeed(track)
            }
        }
    }

    private fun joinRadioWithTrack(track: MusicTrack, snapshot: MusicOneUiState) {
        val current = snapshot.currentTrack
        if (current != null && current.source == track.source && current.id == track.id) {
            togglePlay()
            return
        }
        val existingTrack = snapshot.queue.firstOrNull { it.source == track.source && it.id == track.id }
        if (existingTrack != null) {
            playbackHistory.remember(current?.id, existingTrack.id)
            startTrackFromQueue(existingTrack)
            return
        }
        val queue = radioController.insert(snapshot.queue, current?.takeIf { it.source == radioSource }, track)
        playbackHistory.remember(current?.id, track.id)
        startTrack(track, queue, preserveRadio = true)
        prefetchRadioIfNeeded()
    }

    fun playFeedTrack(track: MusicTrack) = playRecommendationTrack(track)

    fun playPlaylist(playlist: MusicPlaylist, shuffle: Boolean = false) {
        if (playlist.tracks.isNotEmpty() && playlist.tracks.none(::queueTrackSourceAvailable)) {
            rejectUnavailableQueueTrack(playlist.tracks.first())
            return
        }
        failureRecovery.reset()
        _state.update { it.withPlaylistQueue(playlist.tracks, shuffle = shuffle, resetRepeatMode = true) }
        val queue = _state.value.queue
        playbackHistory.clear()
        val track = queue.firstOrNull(::queueTrackSourceAvailable)
        if (track == null) {
            stopRadio()
            _state.update { it.copy(queue = queue) }
        }
        else startTrack(track, queue)
    }

    fun togglePlay() = setPlaying(!_state.value.isPlaying)

    fun playPlaylistTrack(playlist: MusicPlaylist, track: MusicTrack) {
        if (rejectUnavailableQueueTrack(track)) return
        _state.update { it.withPlaylistQueue(playlist.tracks, it.shuffle, selectedTrack = track) }
        playbackHistory.clear()
        playTrack(track)
    }

    fun refreshLibraryMetadata(tracks: List<MusicTrack>) {
        _state.update { it.withLibraryMetadata(tracks) }
        _state.value.currentTrack?.let { playbackSession.refreshMetadata(it, PlaybackRequestKey(it.id, playbackRequestGeneration), ::isCurrentRequest) }
    }

    fun refreshManualLyrics() {
        val track = _state.value.currentTrack?.takeIf { it.source.supportsManualLyrics } ?: return
        loadLyrics(track.copy(lyrics = emptyList()), playbackRequestGeneration)
    }

    /** AI 歌词层变化可能涉及任何音源的歌曲，不受手动歌词权限限制。 */
    fun refreshLyrics() {
        val track = _state.value.currentTrack ?: return
        loadLyrics(track.copy(lyrics = emptyList()), playbackRequestGeneration)
    }

    fun retryLyrics() {
        val current = _state.value
        val track = current.currentTrack ?: return
        if (current.lyricLoadState != LyricLoadState.RETRYABLE) return
        _state.update { it.copy(lyricLoadState = LyricLoadState.LOADING) }
        lyricsLoader.load(track, PlaybackRequestKey(track.id, playbackRequestGeneration), retryAfterPlayback = false)
    }

    internal fun pauseForUsbOutputLoss() {
        playWhenReadyRequested = false
        setPlaying(false)
        _state.value.currentTrack?.let { track ->
            _playbackActivity.value = PlaybackActivity(track.id, false)
            playbackPresentation.present(playbackPresentationKey(track), false)
        }
    }

    internal fun setPlaying(shouldPlay: Boolean) {
        if (shouldPlay) failureRecovery.reset()
        val snapshot = _state.value
        val current = snapshot.currentTrack ?: return
        if (shouldPlay && rejectUnavailableQueueTrack(current)) return
        if (shouldPlay && !current.playable && current.source == MusicSource.LOCAL) {
            _state.update { it.copy(playbackMessage = current.unavailableReason ?: "文件已失联，请重新关联") }
            return
        }
        // 浏览目标尚未正式提交时只更新播放意图，不能重新加载仍留在正式状态里的旧歌。
        if (rapidTrackSwitch.presentation.value != null || trackLoader.isResolving) {
            playWhenReadyRequested = shouldPlay
            playbackPresentation.present(playbackPresentationKey(current), shouldPlay)
            return
        }
        if (shouldPlay &&
            (current.previewUrl.isBlank() || !playerMatchesCurrentTrack() || playbackSession.player?.playerError != null)
        ) {
            startTrack(
                current,
                snapshot.queue,
                initialPositionMs = _progressMs.value,
                preserveRadio = snapshot.radioActive,
            )
            return
        }
        playWhenReadyRequested = shouldPlay
        playbackPresentation.present(playbackPresentationKey(current), shouldPlay)
        val key = PlaybackRequestKey(current.id, playbackRequestGeneration)
        playbackSession.whenReady { player ->
            if (isCurrentRequest(key) && playerMatchesCurrentTrack()) {
                if (playWhenReadyRequested) player.play() else player.pause()
            }
        }
        if (!shouldPlay) {
            val position = currentPlaybackPosition()
            updateProgress(position)
            playbackSession.save(_state.value, position)
        }
    }

    fun next() {
        failureRecovery.reset()
        rapidTrackSwitch.cancel()
        advance(automatic = false)
    }

    fun requestNext() { failureRecovery.reset(); rapidTrackSwitch.request(next = true) }

    fun requestPrevious() { failureRecovery.reset(); rapidTrackSwitch.request(next = false) }

    internal fun previewMiniSwipe(next: Boolean): MusicTrack? = miniPlayerNeighbor(_state.value, playbackHistory, next)

    internal fun commitMiniSwipe(track: MusicTrack, next: Boolean) {
        val snapshot = _state.value
        if (snapshot.queue.none { it.id == track.id }) return
        if (next) playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        else if (snapshot.shuffle) playbackHistory.takePrevious(snapshot.queue.mapTo(hashSetOf(), MusicTrack::id))
        startTrackFromQueue(
            track,
            if (next) TrackTransitionDirection.NEXT else TrackTransitionDirection.PREVIOUS,
        )
        if (snapshot.radioActive) prefetchRadioIfNeeded()
    }

    private fun advance(automatic: Boolean) {
        val snapshot = _state.value
        val current = snapshot.currentTrack ?: return
        val queue = snapshot.queue
        if (queue.isEmpty()) return
        val nextIndex = nextQueuePlaybackIndex(snapshot, automatic)
        if (queue.none(::queueTrackSourceAvailable)) {
            stopQueueWithoutSource(current)
            return
        }
        if (snapshot.radioActive) {
            val nextTrack = nextIndex?.let(queue::get)
            if (nextTrack == null) {
                radioController.advance()
            } else {
                if (automatic && rapidTrackSwitch.handoff(nextTrack, TrackTransitionDirection.NEXT)) return
                playbackHistory.remember(current.id, nextTrack.id)
                startTrackFromQueue(nextTrack)
                prefetchRadioIfNeeded()
            }
            return
        }
        if (nextIndex == null) {
            playWhenReadyRequested = false
            playbackSession.whenReady(Player::pause)
            updateProgress(current.durationMs, current.id)
            _playbackActivity.value = PlaybackActivity(current.id, false)
            playbackPresentation.transportStopped(playbackPresentationKey(current))
        } else {
            val nextTrack = queue[nextIndex]
            if (automatic && nextTrack.id != current.id &&
                rapidTrackSwitch.handoff(nextTrack, TrackTransitionDirection.NEXT)) return
            playbackHistory.remember(current.id, nextTrack.id)
            startTrackFromQueue(nextTrack)
        }
    }

    fun previous() {
        failureRecovery.reset()
        rapidTrackSwitch.cancel()
        val current = _state.value.currentTrack ?: return
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        if (_state.value.radioActive) {
            previousSourceRadioTrack(queue, current.id)?.let {
                startTrackFromQueue(it, TrackTransitionDirection.PREVIOUS)
            }
            return
        }
        val index = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        if (_state.value.shuffle) {
            val previousId = playbackHistory.takePrevious(queue.mapTo(hashSetOf(), MusicTrack::id))
            queue.firstOrNull { it.id == previousId }?.let {
                startTrackFromQueue(it, TrackTransitionDirection.PREVIOUS)
                return
            }
        }
        startTrackFromQueue(
            queue[(index - 1 + queue.size) % queue.size],
            TrackTransitionDirection.PREVIOUS,
        )
    }

    fun seekTo(fraction: Float) {
        val current = _state.value.currentTrack ?: return
        seekToPosition((current.durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    internal fun seekFromSystem(trackId: String, positionMs: Long) {
        if (_state.value.currentTrack?.id != trackId || !playerMatchesCurrentTrack()) return
        val duration = playbackSession.player?.duration?.takeIf { it > 0L }
        seekToPosition(if (duration != null) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L))
    }

    private fun seekToPosition(positionMs: Long) {
        val current = _state.value.currentTrack ?: return
        val progress = positionMs.coerceAtLeast(0L)
        updateProgress(progress, current.id)
        seekPreview.completeProgressCommit(_playbackProgress.value)
        playbackSession.seek(PlaybackRequestKey(current.id, playbackRequestGeneration), progress, ::isCurrentRequest)
        playbackSession.save(_state.value, progress)
    }

    internal fun seekToForTrack(trackId: String, fraction: Float, commitRevision: Long? = null): Boolean {
        if (commitRevision != null && seekPreview.progressCommit.value?.revision != commitRevision) return false
        if (_state.value.currentTrack?.id != trackId) return false
        seekTo(fraction)
        return true
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _state.update { it.copy(playerExpanded = expanded) }
    }

    fun startSourceRadio(sourceId: String) {
        val target = MusicSource.fromId(sourceId)
        val snapshot = _state.value
        // 正在听其他音源的电台时不能只展开播放页，否则后续补歌会混入新音源。
        if (radioSource == target) {
            if (snapshot.radioActive && snapshot.currentTrack != null) {
                setPlayerExpanded(true)
                if (!snapshot.isPlaying) togglePlay()
                return
            }
            if (snapshot.radioLoading) return
        }
        radioSource = target
        radioController.start()
    }

    fun cyclePlaybackMode() {
        if (_state.value.radioActive) return
        _state.update {
            val next = playerPlaybackMode(it.shuffle, it.repeatMode).next()
            it.withPlaybackMode(next)
        }
        playbackSession.save(_state.value, _progressMs.value)
    }

    fun selectQuality(quality: AudioQuality) {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        qualityController.select(track, PlaybackRequestKey(track.id, playbackRequestGeneration),
            quality, snapshot.activeQuality, snapshot.availableQualities)
    }

    private fun onQualityEvent(event: PlaybackQualityEvent) {
        if (!isCurrentRequest(event.key)) return
        val quality = when (event) {
            is PlaybackQualityEvent.Selected -> event.quality
            is PlaybackQualityEvent.Upgraded -> event.source.actualQuality
            else -> null
        }
        if (quality != null && !DolbyPlaybackPolicy.permits(quality)) {
            restartWithoutDolby()
            return
        }
        val replaceSource = (event is PlaybackQualityEvent.Selected && event.url != null) ||
            event is PlaybackQualityEvent.Upgraded
        val position = if (replaceSource) currentPlaybackPosition() else 0L
        _state.update { it.withQualityEvent(event) }
        if (replaceSource) _state.value.currentTrack?.let {
            startPlayback(it, position, event.key.generation)
        }
    }

    private fun onLyricsUpdate(update: PlaybackLyricsUpdate) {
        if (isCurrentRequest(update.key)) _state.update { it.withLyricsUpdate(update) }
    }

    fun refreshQualityOptions(interactive: Boolean = false) {
        _state.value.currentTrack?.let {
            qualityController.loadOptions(it, PlaybackRequestKey(it.id, playbackRequestGeneration), interactive = interactive)
        }
    }

    /** 打开音质菜单时调用，沿用进行中或已完成的查询，避免每次展开都从头探测。 */
    fun ensureQualityOptions() {
        _state.value.currentTrack?.let {
            qualityController.ensureOptions(it, PlaybackRequestKey(it.id, playbackRequestGeneration))
        }
    }

    private fun startTrackFromQueue(
        track: MusicTrack,
        direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
        preserveRapidSwitch: Boolean = false,
        playWhenReady: Boolean = true,
    ) {
        val snapshot = _state.value
        val queue = if (snapshot.radioActive) {
            radioController.center(snapshot.queue, track.id)
        } else {
            snapshot.queue
        }
        startTrack(track, queue, preserveRadio = snapshot.radioActive, direction = direction,
            preserveRapidSwitch = preserveRapidSwitch, playWhenReady = playWhenReady)
    }

    private fun beginRapidTrackBrowsing() {
        nextAudioPreloader.stop()
        sourcePlaybackSessions.pause(currentPlaybackPosition())
        // 首击可能仍在解析，进入浏览后取消它，避免旧结果在连续歌词动画中途起播。
        ++playbackRequestGeneration
        cancelPendingTrackWork()
        playbackSession.clearPendingSeek()
        seekPreview.clear()
        _playbackActivity.value = PlaybackActivity(_state.value.currentTrack?.id, false)
        playbackSession.whenReady(Player::pause)
    }

    private fun commitRapidTrackSwitch(track: MusicTrack, direction: TrackTransitionDirection) {
        val snapshot = _state.value
        if (snapshot.queue.none { it.id == track.id }) return
        // 浏览期间已暂停并取消首击请求，绕回同一首也必须重新交付最终播放。
        if (direction == TrackTransitionDirection.NEXT) {
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        } else if (snapshot.shuffle) {
            playbackHistory.takePrevious(snapshot.queue.mapTo(hashSetOf(), MusicTrack::id))
        }
        startTrackFromQueue(track, direction, preserveRapidSwitch = true,
            playWhenReady = playWhenReadyRequested)
        if (snapshot.radioActive) prefetchRadioIfNeeded()
    }

    private fun startTrack(
        track: MusicTrack,
        queue: List<MusicTrack>,
        initialPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        preserveRadio: Boolean = false,
        direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
        preserveRapidSwitch: Boolean = false,
        recoverFrom: PlaybackSource? = null,
    ) {
        if (rejectUnavailableQueueTrack(track)) return
        val previous = _state.value.currentTrack
        sourcePlaybackSessions.prepare(track, currentPlaybackPosition(), continuing = recoverFrom != null ||
            (previous?.id == track.id && previous.source == track.source && initialPositionMs > 0))
        if (!preserveRapidSwitch) rapidTrackSwitch.cancel()
        nextAudioPreloader.stop()
        if (!preserveRadio) stopRadio()
        val requestGeneration = ++playbackRequestGeneration
        favoritePreparation.prepare(track, requestGeneration)
        playbackSession.clearPendingSeek()
        playWhenReadyRequested = playWhenReady
        cancelPendingTrackWork()
        seekPreview.clear()
        _playbackActivity.value = PlaybackActivity(track.id, false)
        playbackSession.whenReady(Player::pause)
        val startingPosition = initialPositionMs.coerceIn(0L, track.durationMs.coerceAtLeast(0L))
        _state.update {
            // QQ 歌词窗口等本地缓存解析完成后再交接，快速掠过未缓存歌曲时保留当前窗口。
            val presentedTrack = track.forPendingPlaybackPresentation()
            it.forTrackTransition(presentedTrack, queue, playWhenReady, direction)
                .copy(queueOrder = (when {
                    preserveRadio -> PlaybackQueueOrder()
                    queue === it.queue -> it.queueOrder
                    else -> it.queueOrder.reconcile(queue)
                }).afterTrackChange(track.id))
        }
        updateProgress(startingPosition, track.id)
        playbackPresentation.present(PlaybackPresentationKey(track.id, requestGeneration), playWhenReady)
        playbackSession.resetProgressBucket(startingPosition)
        playbackSession.save(_state.value, _progressMs.value)
        // 允许手动歌词的音源先装载资料库覆盖，无覆盖时仍可使用在线提供者。
        if (track.source.supportsManualLyrics) loadLyrics(track, requestGeneration)
        resolveAndStart(track, requestGeneration, _progressMs.value, playWhenReady,
            lyricsHandedOff = preserveRapidSwitch, recoverFrom = recoverFrom)
    }

    private fun prefetchRadioIfNeeded() = radioController.prefetch()

    private fun onRadioEvent(event: SourceRadioEvent) {
        _state.update { it.withSourceRadioEvent(event) }
        when (event) {
            // 每次新会话前控制器都会先结束旧会话，续页在这里作废。
            is SourceRadioEvent.Mode -> if (!event.active) radioRepository.resetSession()
            is SourceRadioEvent.Started -> {
                playbackHistory.clear()
                startTrack(event.queue.first(), event.queue, preserveRadio = true)
            }
            is SourceRadioEvent.Appended -> if (event.advance) {
                val currentId = _state.value.currentTrack?.id
                nextSourceRadioTrack(event.queue, currentId)?.let {
                    currentId?.let { previous -> playbackHistory.remember(previous, it.id) }
                    startTrackFromQueue(it)
                }
            }
            is SourceRadioEvent.Failed -> if (event.pause) playbackSession.whenReady(Player::pause)
            else -> Unit
        }
    }

    private fun cancelPendingTrackWork() {
        trackLoader.cancel()
        lyricsLoader.cancel()
        qualityController.cancel()
    }

    private fun stopRadio() = radioController.stop()

    private fun resolveAndStart(track: MusicTrack, requestGeneration: Long, initialPositionMs: Long = 0L,
                                playWhenReady: Boolean = true, lyricsHandedOff: Boolean = false,
                                recoverFrom: PlaybackSource? = null) {
        val preferred = qualityPreferences.read(track.source)
        val effective = PlaybackOptions.effective(application, preferred, track.source).playbackEquivalent()
        trackLoader.start(track, PlaybackRequestKey(track.id, requestGeneration), preferred, effective,
            initialPositionMs, playWhenReady, lyricsHandedOff, previousSource = recoverFrom)
    }

    private fun onTrackEvent(event: PlaybackTrackEvent) {
        if (!isCurrentRequest(event.key)) return
        val generation = event.key.generation
        when (event) {
            is PlaybackTrackEvent.Ready -> {
                val resolved = event.playback
                if (!DolbyPlaybackPolicy.permits(resolved.source.actualQuality)) {
                    restartWithoutDolby()
                    return
                }
                if (!resolved.source.trial) qualityCache.write(resolved.track, resolved.source.actualQuality)
                _state.update { it.withResolvedPlayback(event) }
                startPlayback(resolved.track, event.positionMs, generation)
                qualityController.followCachedPlayback(resolved, event.key, event.effective, event.preferred)
                if (!resolved.track.source.supportsManualLyrics || !lyricsLoader.hasRequest(event.key)) {
                    loadLyrics(resolved.track, generation, PLAYBACK_LYRICS_DELAY_MS)
                }
                loadQualityOptions(resolved.track, generation, PLAYBACK_QUALITY_DELAY_MS)
            }
            is PlaybackTrackEvent.Metadata -> {
                _state.update { it.withPlaybackMetadata(event) }
                val updated = _state.value.currentTrack ?: return
                playbackSession.refreshMetadata(updated, event.key, ::isCurrentRequest)
                // 信息流补齐身份后重试档位探测和已播上报，保留当前音源与歌词。
                if (event.original.durationMs <= 0L || updated.qualityIds.isEmpty()) loadQualityOptions(updated, generation)
                if (playbackSession.player?.isPlaying == true) reportRecentPlay(updated, generation)
            }
            is PlaybackTrackEvent.Failed -> {
                sourcePlaybackSessions.finish(SourcePlaybackSessionEndReason.ERROR, currentPlaybackPosition())
                if (recoverUnavailableTrack(event.message)) return
                playWhenReadyRequested = false
                if (_state.value.currentTrack?.source?.playbackBehavior?.pauseOnFailure == true) {
                    _playbackActivity.value = PlaybackActivity(event.key.trackId, false)
                    playbackSession.whenReady(Player::pause)
                }
                playbackPresentation.transportStopped(PlaybackPresentationKey(event.key.trackId, generation))
                _state.update { it.copy(isPlaying = if (it.currentTrack?.source?.playbackBehavior?.pauseOnFailure == true) false else it.isPlaying,
                    qualityLoading = false, lyricLoadState = LyricLoadState.UNAVAILABLE,
                    qualityChanging = false, playbackMessage = event.message) }
            }
        }
    }

    private fun recoverUnavailableTrack(message: String): Boolean {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return false
        if (!playWhenReadyRequested) return false
        Log.w("MusicPlayback", "歌曲无法播放：$message")
        when (val outcome = failureRecovery.fail(track, snapshot.queue, ::queueTrackSourceAvailable)) {
            is PlaybackFailureOutcome.Skip -> {
                startTrack(outcome.next, snapshot.queue, preserveRadio = snapshot.radioActive)
                PlaybackFailureNotices.show("《${track.title}》无法播放，已跳过")
            }
            PlaybackFailureOutcome.LimitReached, PlaybackFailureOutcome.Exhausted -> {
                val notice = if (outcome == PlaybackFailureOutcome.LimitReached)
                    "连续 $PLAYBACK_CONSECUTIVE_FAILURE_LIMIT 首无法播放，已暂停" else "队列中暂无可用歌曲，已暂停"
                playWhenReadyRequested = false
                playbackSession.whenReady(Player::pause)
                playbackPresentation.transportStopped(playbackPresentationKey(track))
                _state.update { it.withExhaustedPlaybackQueue(notice) }
                PlaybackFailureNotices.show(notice)
            }
        }
        return true
    }

    private fun loadQualityOptions(track: MusicTrack, requestGeneration: Long, startDelayMs: Long = 0L) =
        qualityController.loadOptions(track, PlaybackRequestKey(track.id, requestGeneration), startDelayMs)

    private fun loadLyrics(track: MusicTrack, requestGeneration: Long, startDelayMs: Long = 0L) =
        lyricsLoader.load(track, PlaybackRequestKey(track.id, requestGeneration), startDelayMs)

    private fun isCurrentRequest(key: PlaybackRequestKey) = isCurrentRequest(key.generation, key.trackId)

    private fun isCurrentRequest(requestGeneration: Long, trackId: String): Boolean =
        isCurrentPlaybackRequest(
            requestGeneration,
            playbackRequestGeneration,
            trackId,
            _state.value.currentTrack?.id,
        )

    private fun playerMatchesCurrentTrack(): Boolean =
        playbackSession.matches(playbackRequestGeneration, _state.value.currentTrack?.id)

    private fun currentPlaybackPosition(): Long =
        playbackSession.position(playbackRequestGeneration, _state.value.currentTrack?.id, _progressMs.value)

    private fun startPlayback(track: MusicTrack, positionMs: Long = 0L,
                              requestGeneration: Long = playbackRequestGeneration) {
        if ((_state.value.activeQuality?.capabilityQuality == AudioQuality.DOLBY || resolvedPlaybackRequiresDolby(track.previewUrl.orEmpty())) && !DolbyPlaybackPolicy.eligibility.allowed) {
            restartWithoutDolby()
            return
        }
        playbackSession.start(track, PlaybackRequestKey(track.id, requestGeneration), positionMs, { playWhenReadyRequested },
            ::isCurrentRequest, started = { player, startingPosition ->
                playbackSession.remember(_state.value, track.id)
                updateProgress(startingPosition, track.id)
                _playbackActivity.value = PlaybackActivity(track.id, player.isPlaying)
            }, failed = {
                playWhenReadyRequested = false
                playbackPresentation.transportStopped(PlaybackPresentationKey(track.id, requestGeneration))
            })
    }

    private fun restartWithoutDolby() {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        val position = currentPlaybackPosition()
        sourcePlaybackSessions.pause(position)
        val shouldPlay = playWhenReadyRequested
        val current = snapshot.qualityTarget ?: SupplementalPlaybackRequests.resource(track.previewUrl)?.actualQuality
            ?: snapshot.activeQuality
        val compatible = compatibleNonDolbyQuality(track.source, current)
        val generation = ++playbackRequestGeneration
        cancelPendingTrackWork()
        nextAudioPreloader.stop()
        playbackSession.whenReady(Player::pause)
        if (compatible == null) {
            playWhenReadyRequested = false
            _state.update { it.copy(qualityChanging = false, qualityTarget = null,
                qualityError = "音源未声明可用的兼容回退音质", isPlaying = false) }
            return
        }
        _state.update { it.copy(qualityChanging = true, qualityTarget = compatible,
            qualityError = null, playbackMessage = "正在切换至兼容音质") }
        val effective = PlaybackOptions.effective(application, compatible, track.source).playbackEquivalent()
        trackLoader.start(track, PlaybackRequestKey(track.id, generation), qualityPreferences.read(track.source),
            effective, position, shouldPlay, lyricsHandedOff = true)
    }

    private fun updateProgress(positionMs: Long, trackId: String? = _state.value.currentTrack?.id) {
        val position = positionMs.coerceAtLeast(0L)
        _progressMs.value = position
        _playbackProgress.value = PlaybackProgressSnapshot(trackId, position, playbackRequestGeneration,
            android.os.SystemClock.elapsedRealtime())
    }

    private fun reportRecentPlay(track: MusicTrack, generation: Long) = recentPlayReporter.report(track, generation,
        playedDuration.record(generation, playbackSession.player?.isPlaying == true && playerMatchesCurrentTrack()))

    private fun playbackPresentationKey(track: MusicTrack): PlaybackPresentationKey =
        PlaybackPresentationKey(track.id, playbackRequestGeneration)

    internal fun preloadForegroundChanged(visible: Boolean) { nextAudioPreloader.foregroundChanged(visible) }

    internal fun attachPlayer(player: Player, bandwidth: PlaybackPreloadBandwidth) {
        nextAudioPreloader.attachPlayer(player, bandwidth)
        player.addListener(playerListener)
        playbackSession.attach(player)
    }

    internal fun detachPlayer(player: Player) {
        if (playbackSession.player !== player) return
        val position = currentPlaybackPosition()
        sourcePlaybackSessions.finish(SourcePlaybackSessionEndReason.STOPPED, position)
        playbackSession.save(_state.value, position)
        ++playbackRequestGeneration
        cancelPendingTrackWork()
        rapidTrackSwitch.cancel()
        nextAudioPreloader.stop()
        playWhenReadyRequested = false
        playbackPresentation.cancel()
        _playbackActivity.value = PlaybackActivity(_state.value.currentTrack?.id, false)
        _state.update { it.copy(isPlaying = false, qualityLoading = false, qualityChanging = false) }
        updateProgress(position)
        player.removeListener(playerListener)
        nextAudioPreloader.detachPlayer()
        playbackSession.detach(player)
    }

    internal fun release() {
        nextAudioPreloader.stop()
        playbackPresentation.cancel()
        val position = currentPlaybackPosition()
        val snapshot = _state.value
        sourcePlaybackSessions.close(position)
        cancelPendingTrackWork()
        radioController.stop()
        playbackSession.close(snapshot, position, playerListener)
        scope.cancel()
    }

}
