package com.musicone.demo

import android.app.Application
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
    private val nextAudioPreloader = QqNextAudioPreloader(application, scope)
    private val qualityPreferences = PlaybackQualityPreferences(application)
    private val playbackSession = PlaybackSessionCoordinator(application)
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
            if (track.source.supportsLibraryEditing) playbackResolver.lyrics(track) else cachedLyrics.read(track)
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
        waitForSingleLyrics = { _state.value.currentTrack?.source == MusicSource.QQ },
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
        { playbackSession.player?.isPlaying == true }, ::onTrackEvent, PlaybackResolutionWakeGuard(application))
    private val lyricsLoader = PlaybackLyricsLoader(scope, playbackResolver::lyrics,
        ::isCurrentRequest, ::onLyricsUpdate)
    private val qualityController = PlaybackQualityController(
        scope, playbackResolver::availableQualities, playbackResolver::resolveExact,
        playbackResolver::resolvePreferred, qualityPreferences::save,
        { source -> PlaybackOptions.effective(application, qualityPreferences.read(source), source).playbackEquivalent() },
        ::isCurrentRequest, { playWhenReadyRequested && playbackSession.player?.isPlaying != true },
        ::onQualityEvent,
    )
    private val qqRadio = QqRadioController(scope, QqRadioRepository(application)::load,
        { QqRadioQueueSnapshot(_state.value.queue, _state.value.currentTrack) }, ::onRadioEvent)
    private val recentPlayReporter = QqRecentPlayReporter(scope, QqRecentPlayRepository(application)::report)
    private val queueRemoval = PlaybackQueueRemoval(_state,
        startNext = { track, remaining ->
            failureRecovery.reset()
            startTrack(track, remaining.queue, playWhenReady = playWhenReadyRequested,
                preserveQqRadio = remaining.qqRadioActive)
            if (remaining.qqRadioActive) prefetchQqRadioIfNeeded()
        },
        clearPlayback = ::clearRemovedQueue,
        save = { playbackSession.save(_state.value, currentPlaybackPosition()) })

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (!playerMatchesCurrentTrack()) return
            val track = _state.value.currentTrack ?: return
            if (isUsbOutputFailure(error, PlaybackOptions.usbLosslessOutput)) {
                pauseForUsbOutputLoss()
                return
            }
            if (_state.value.activeQuality == AudioQuality.DOLBY || qqPlaybackQuality(track.previewUrl.orEmpty()) == AudioQuality.DOLBY) {
                restartWithoutDolby()
                return
            }
            val causeType = generateSequence<Throwable>(error) { it.cause }.last()::class.java.simpleName
            Log.w("MusicPlayback", "Media3 播放失败：errorCode=${error.errorCode}，cause=$causeType")
            if (playWhenReadyRequested && failureRecovery.mayRenew(track)) {
                startTrack(track.copy(previewUrl = ""), _state.value.queue, currentPlaybackPosition(), preserveQqRadio = _state.value.qqRadioActive)
                return
            }
            if (track.source != MusicSource.KUGOU && recoverUnavailableTrack("播放失败")) return
            if (track.source == MusicSource.KUGOU) {
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
            val position = currentPlaybackPosition()
            updateProgress(position, track.id)
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
            if (!allowed && (snapshot.activeQuality == AudioQuality.DOLBY ||
                    snapshot.qualityTarget == AudioQuality.DOLBY ||
                    qqPlaybackQuality(snapshot.currentTrack?.previewUrl.orEmpty()) == AudioQuality.DOLBY)) {
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
                        val quality = if (current.currentTrack?.source == MusicSource.QQ) qqPlaybackQuality(result.first).takeIf { result.second } else current.activeQuality
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
            { playbackSession.player })
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (!_playbackActivity.value.advancing || !playerMatchesCurrentTrack()) continue
                val position = currentPlaybackPosition()
                updateProgress(position)
                playbackSession.saveProgressIfNeeded(_state.value, position)
                prefetchQqRadioIfNeeded()
            }
        }
    }

    fun configureSource(source: MusicSource) {
        // 浏览入口切换不接管或替换当前混合队列，包括正在解析的曲目。
        if (_state.value.currentTrack != null) return
        // 页面重建时沿用服务当前请求，不能取消后台正在解析的下一首。
        if (_state.value.currentTrack?.source == source &&
            (playerMatchesCurrentTrack() || trackLoader.isResolving || rapidTrackSwitch.presentation.value != null)) return
        stopQqRadio()
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
            if (qqPlaybackQuality(track?.previewUrl.orEmpty()) == AudioQuality.DOLBY && !DolbyPlaybackPolicy.eligibility.allowed) {
                restartWithoutDolby()
            } else track?.let { loadLyrics(it, generation) }
        }
    }

    private fun restoreRadioQueue(state: MusicOneUiState): MusicOneUiState =
        if (state.qqRadioActive) state.copy(
            queue = qqRadio.restore(state.queue, state.currentTrack?.id),
            shuffle = false, repeatMode = RepeatMode.ALL,
        ) else state

    fun setPage(page: MusicOnePage) {
        _state.update { it.copy(page = page) }
    }

    fun playTrack(track: MusicTrack) {
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
        if (snapshot.qqRadioActive && snapshot.queue.any { it.id == track.id }) {
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
            startTrackFromQueue(track)
            prefetchQqRadioIfNeeded()
            return
        }
        val existingQueue = snapshot.queue
        val queue = if (existingQueue.any { it.id == track.id }) {
            existingQueue
        } else {
            existingQueue.take(999) + track
        }
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        startTrack(track, queue)
    }

    fun removeTrackFromQueue(track: MusicTrack) = queueRemoval.remove(track)

    private fun clearRemovedQueue() {
        val generation = ++playbackRequestGeneration
        playWhenReadyRequested = false
        failureRecovery.reset()
        rapidTrackSwitch.cancel()
        nextAudioPreloader.stop()
        cancelPendingTrackWork()
        playbackPresentation.cancel()
        playbackHistory.clear()
        seekPreview.clear()
        stopQqRadio()
        _playbackActivity.value = PlaybackActivity()
        updateProgress(0L)
        playbackSession.clearQueue { playbackRequestGeneration == generation && _state.value.currentTrack == null }
    }

    fun insertTrackNext(track: MusicTrack) {
        if (_state.value.currentTrack == null) { playTrack(track); return }
        val wasRadio = _state.value.qqRadioActive
        _state.update { it.withTrackInsertedNext(track) }
        if (wasRadio && !_state.value.qqRadioActive) stopQqRadio()
        playbackSession.save(_state.value, currentPlaybackPosition())
    }

    fun playTrackNext(track: MusicTrack) {
        failureRecovery.reset()
        val snapshot = _state.value
        if (snapshot.currentTrack?.let { it.source == track.source && it.id == track.id } == true) {
            playTrack(track)
            return
        }
        if (snapshot.qqRadioActive && track.source == MusicSource.QQ) {
            val queue = qqRadio.insert(snapshot.queue, snapshot.currentTrack, track)
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
            startTrack(track, queue, preserveQqRadio = true)
            prefetchQqRadioIfNeeded()
            return
        }
        val existingQueue = snapshot.queue
        val queue = insertTrackAfterCurrent(existingQueue, snapshot.currentTrack, track)
        _state.update { it.copy(queueOrder = it.queueOrder.insert(existingQueue, snapshot.currentTrack, track)) }
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        startTrack(track, queue)
    }

    fun playQqRecommendationTrack(track: MusicTrack) {
        val snapshot = _state.value
        val isSameTrack: (MusicTrack?) -> Boolean = { it != null && it.source == track.source && it.id == track.id }
        if (snapshot.qqRadioActive) {
            if (isSameTrack(snapshot.currentTrack)) {
                togglePlay()
                return
            }
            val existingIndex = snapshot.queue.indexOfFirst { it.source == track.source && it.id == track.id }
            if (existingIndex >= 0) {
                val existingTrack = snapshot.queue[existingIndex]
                playbackHistory.remember(snapshot.currentTrack?.id, existingTrack.id)
                startTrackFromQueue(existingTrack)
                return
            }
            val queue = qqRadio.insert(
                snapshot.queue,
                snapshot.currentTrack?.takeIf { it.source == MusicSource.QQ },
                track,
            )
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
            startTrack(track, queue, preserveQqRadio = true)
            prefetchQqRadioIfNeeded()
            return
        }
        stopQqRadio()
        playbackHistory.clear()
        qqRadio.startWithSeed(track)
    }

    fun playQqFeedTrack(track: MusicTrack) = playQqRecommendationTrack(track)

    fun playPlaylist(playlist: MusicPlaylist, shuffle: Boolean = false) {
        failureRecovery.reset()
        _state.update { it.withPlaylistQueue(playlist.tracks, shuffle = shuffle, resetRepeatMode = true) }
        val queue = _state.value.queue
        playbackHistory.clear()
        val track = queue.firstOrNull()
        if (track == null) {
            stopQqRadio()
            _state.update { it.copy(queue = queue) }
        }
        else startTrack(track, queue)
    }

    fun togglePlay() = setPlaying(!_state.value.isPlaying)

    fun playPlaylistTrack(playlist: MusicPlaylist, track: MusicTrack) {
        _state.update { it.withPlaylistQueue(playlist.tracks, it.shuffle) }
        playbackHistory.clear()
        playTrack(track)
    }

    fun refreshLibraryMetadata(tracks: List<MusicTrack>) {
        _state.update { it.withLibraryMetadata(tracks) }
        _state.value.currentTrack?.let { playbackSession.refreshMetadata(it, PlaybackRequestKey(it.id, playbackRequestGeneration), ::isCurrentRequest) }
    }

    fun refreshManualLyrics() {
        val track = _state.value.currentTrack?.takeIf { it.source.supportsLibraryEditing } ?: return
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
        _state.update { it.copy(playbackMessage = "USB 音频输出已断开，已暂停") }
    }

    internal fun setPlaying(shouldPlay: Boolean) {
        if (shouldPlay) failureRecovery.reset()
        val snapshot = _state.value
        val current = snapshot.currentTrack ?: return
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
                preserveQqRadio = snapshot.qqRadioActive,
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
        if (snapshot.qqRadioActive) prefetchQqRadioIfNeeded()
    }

    private fun advance(automatic: Boolean) {
        val current = _state.value.currentTrack ?: return
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        if (_state.value.qqRadioActive) {
            val nextTrack = nextQqRadioTrack(queue, current.id)
            if (nextTrack == null) {
                qqRadio.advance()
            } else {
                if (automatic && rapidTrackSwitch.handoff(nextTrack, TrackTransitionDirection.NEXT)) return
                playbackHistory.remember(current.id, nextTrack.id)
                startTrackFromQueue(nextTrack)
                prefetchQqRadioIfNeeded()
            }
            return
        }
        val nextIndex = _state.value.insertedNextIndex()
            ?: nextQueueIndex(index, queue.size, _state.value.shuffle, _state.value.repeatMode, automatic)
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
        if (_state.value.qqRadioActive) {
            previousQqRadioTrack(queue, current.id)?.let {
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

    fun startQqRadio() {
        val snapshot = _state.value
        if (snapshot.qqRadioActive && snapshot.currentTrack != null) {
            setPlayerExpanded(true)
            if (!snapshot.isPlaying) togglePlay()
            return
        }
        if (snapshot.qqRadioLoading) return
        qqRadio.start()
    }

    fun cyclePlaybackMode() {
        if (_state.value.qqRadioActive) return
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

    fun refreshQualityOptions() {
        _state.value.currentTrack?.let { loadQualityOptions(it, playbackRequestGeneration) }
    }

    private fun startTrackFromQueue(
        track: MusicTrack,
        direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
        preserveRapidSwitch: Boolean = false,
        playWhenReady: Boolean = true,
    ) {
        val snapshot = _state.value
        val queue = if (snapshot.qqRadioActive) {
            qqRadio.center(snapshot.queue, track.id)
        } else {
            snapshot.queue
        }
        startTrack(track, queue, preserveQqRadio = snapshot.qqRadioActive, direction = direction,
            preserveRapidSwitch = preserveRapidSwitch, playWhenReady = playWhenReady)
    }

    private fun beginRapidTrackBrowsing() {
        nextAudioPreloader.stop()
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
        if (snapshot.qqRadioActive) prefetchQqRadioIfNeeded()
    }

    private fun startTrack(
        track: MusicTrack,
        queue: List<MusicTrack>,
        initialPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        preserveQqRadio: Boolean = false,
        direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
        preserveRapidSwitch: Boolean = false,
    ) {
        if (!preserveRapidSwitch) rapidTrackSwitch.cancel()
        nextAudioPreloader.stop()
        if (!preserveQqRadio) stopQqRadio()
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
                    preserveQqRadio -> PlaybackQueueOrder()
                    queue === it.queue -> it.queueOrder
                    else -> it.queueOrder.reconcile(queue)
                }).afterTrackChange(track.id))
        }
        updateProgress(startingPosition, track.id)
        playbackPresentation.present(PlaybackPresentationKey(track.id, requestGeneration), playWhenReady)
        playbackSession.resetProgressBucket(startingPosition)
        playbackSession.save(_state.value, _progressMs.value)
        // 本地与补充音源只读音乐库歌词，不等待在线音源取票。
        if (track.source.supportsLibraryEditing) loadLyrics(track, requestGeneration)
        resolveAndStart(track, requestGeneration, _progressMs.value, playWhenReady,
            lyricsHandedOff = preserveRapidSwitch)
    }

    private fun prefetchQqRadioIfNeeded() = qqRadio.prefetch()

    private fun onRadioEvent(event: QqRadioEvent) {
        _state.update { it.withRadioEvent(event) }
        when (event) {
            is QqRadioEvent.Started -> {
                playbackHistory.clear()
                startTrack(event.queue.first(), event.queue, preserveQqRadio = true)
            }
            is QqRadioEvent.Appended -> if (event.advance) {
                val currentId = _state.value.currentTrack?.id
                nextQqRadioTrack(event.queue, currentId)?.let {
                    currentId?.let { previous -> playbackHistory.remember(previous, it.id) }
                    startTrackFromQueue(it)
                }
            }
            is QqRadioEvent.Failed -> if (event.pause) playbackSession.whenReady(Player::pause)
            else -> Unit
        }
    }

    private fun cancelPendingTrackWork() {
        trackLoader.cancel()
        lyricsLoader.cancel()
        qualityController.cancel()
    }

    private fun stopQqRadio() = qqRadio.stop()

    private fun resolveAndStart(track: MusicTrack, requestGeneration: Long, initialPositionMs: Long = 0L,
                                playWhenReady: Boolean = true, lyricsHandedOff: Boolean = false) {
        val preferred = qualityPreferences.read(track.source)
        val effective = PlaybackOptions.effective(application, preferred, track.source).playbackEquivalent()
        trackLoader.start(track, PlaybackRequestKey(track.id, requestGeneration), preferred, effective,
            initialPositionMs, playWhenReady, lyricsHandedOff)
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
                _state.update { it.withResolvedPlayback(event) }
                startPlayback(resolved.track, event.positionMs, generation)
                qualityController.followCachedPlayback(resolved, event.key, event.effective, event.preferred)
                if (!resolved.track.source.supportsLibraryEditing) {
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
                if (event.maySkip && recoverUnavailableTrack(event.message)) return
                playWhenReadyRequested = false
                if (_state.value.currentTrack?.source == MusicSource.KUGOU) {
                    _playbackActivity.value = PlaybackActivity(event.key.trackId, false)
                    playbackSession.whenReady(Player::pause)
                }
                playbackPresentation.transportStopped(PlaybackPresentationKey(event.key.trackId, generation))
                _state.update { it.copy(isPlaying = if (it.currentTrack?.source == MusicSource.KUGOU) false else it.isPlaying,
                    qualityLoading = false, lyricLoadState = LyricLoadState.UNAVAILABLE,
                    qualityChanging = false, playbackMessage = event.message) }
            }
        }
    }

    private fun recoverUnavailableTrack(message: String): Boolean {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return false
        if (!playWhenReadyRequested) return false
        val next = failureRecovery.next(track, snapshot.queue)
        if (next != null) {
            startTrack(next, snapshot.queue, preserveQqRadio = snapshot.qqRadioActive)
            _state.update { it.copy(playbackMessage = "${track.title}：$message，已跳过") }
        } else {
            playWhenReadyRequested = false
            playbackSession.whenReady(Player::pause)
            playbackPresentation.transportStopped(playbackPresentationKey(track))
            _state.update { it.copy(isPlaying = false, qualityLoading = false, qualityChanging = false,
                playbackMessage = "队列中暂无可用歌曲，已停止重试") }
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
        if ((_state.value.activeQuality == AudioQuality.DOLBY || qqPlaybackQuality(track.previewUrl.orEmpty()) == AudioQuality.DOLBY) && !DolbyPlaybackPolicy.eligibility.allowed) {
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
        val shouldPlay = playWhenReadyRequested
        val generation = ++playbackRequestGeneration
        cancelPendingTrackWork()
        nextAudioPreloader.stop()
        playbackSession.whenReady(Player::pause)
        _state.update { it.copy(qualityChanging = true, qualityTarget = AudioQuality.HI_RES,
            qualityError = null, playbackMessage = "正在切换至兼容音质") }
        val effective = PlaybackOptions.effective(application, AudioQuality.HI_RES).playbackEquivalent()
        trackLoader.start(track, PlaybackRequestKey(track.id, generation), qualityPreferences.read(track.source),
            effective, position, shouldPlay, lyricsHandedOff = true)
    }

    private fun updateProgress(positionMs: Long, trackId: String? = _state.value.currentTrack?.id) {
        val position = positionMs.coerceAtLeast(0L)
        _progressMs.value = position
        _playbackProgress.value = PlaybackProgressSnapshot(trackId, position, playbackRequestGeneration,
            android.os.SystemClock.elapsedRealtime())
    }

    private fun reportRecentPlay(track: MusicTrack, generation: Long) = recentPlayReporter.report(track, generation)

    private fun playbackPresentationKey(track: MusicTrack): PlaybackPresentationKey =
        PlaybackPresentationKey(track.id, playbackRequestGeneration)

    internal fun attachPlayer(player: Player) {
        player.addListener(playerListener)
        playbackSession.attach(player)
    }

    internal fun detachPlayer(player: Player) {
        rapidTrackSwitch.setPresentationActive(false)
        player.removeListener(playerListener)
        playbackSession.save(_state.value, currentPlaybackPosition())
        playbackSession.detach(player)
    }

    internal fun release() {
        nextAudioPreloader.stop()
        playbackPresentation.cancel()
        val position = currentPlaybackPosition()
        val snapshot = _state.value
        cancelPendingTrackWork()
        qqRadio.stop()
        playbackSession.close(snapshot, position, playerListener)
        scope.cancel()
    }

}
