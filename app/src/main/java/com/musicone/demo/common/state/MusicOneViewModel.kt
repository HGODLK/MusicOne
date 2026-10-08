package com.musicone.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MusicOneViewModel(application: Application) : AndroidViewModel(application) {
    internal val playlistLibrary = PlaylistLibrary(application)
    private val playbackResolver = PlatformPlaybackResolver(application)
    private val nextAudioPreloader = QqNextAudioPreloader(application, viewModelScope)
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
        scope = viewModelScope,
        cachedLyrics = cachedLyrics::read,
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
    private var playbackRequestGeneration = 0L
    private var playWhenReadyRequested = false
    private val playbackPresentation = PlaybackPresentationDelay(
        scope = viewModelScope,
        stillStopped = { key ->
            isCurrentRequest(key.generation, key.trackId) && playbackSession.player?.isPlaying != true
        },
        publish = { key, playing ->
            if (isCurrentRequest(key.generation, key.trackId)) {
                _state.update { it.copy(isPlaying = playing) }
            }
        },
    )

    private val trackLoader = PlaybackTrackLoader(viewModelScope, nextAudioPreloader::take,
        playbackResolver::resolve, playbackResolver::enrichMetadata, ::isCurrentRequest,
        { playbackSession.player?.isPlaying == true }, ::onTrackEvent)
    private val lyricsLoader = PlaybackLyricsLoader(viewModelScope, playbackResolver::lyrics,
        ::isCurrentRequest, ::onLyricsUpdate)
    private val qualityController = PlaybackQualityController(
        viewModelScope, playbackResolver::availableQualities, playbackResolver::resolveExact,
        playbackResolver::resolvePreferred, qualityPreferences::save,
        { source -> PlaybackOptions.effective(application, qualityPreferences.read(source)).playbackEquivalent() },
        ::isCurrentRequest, { playWhenReadyRequested && playbackSession.player?.isPlaying != true },
        ::onQualityEvent,
    )
    private val qqRadio = QqRadioController(viewModelScope, QqRadioRepository(application)::load,
        { QqRadioQueueSnapshot(_state.value.queue, _state.value.currentTrack) }, ::onRadioEvent)
    private val recentPlayReporter = QqRecentPlayReporter(viewModelScope, QqRecentPlayRepository(application)::report)

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (!playerMatchesCurrentTrack()) return
            val track = _state.value.currentTrack ?: return
            val causeType = generateSequence<Throwable>(error) { it.cause }.last()::class.java.simpleName
            Log.w("MusicPlayback", "Media3 播放失败：errorCode=${error.errorCode}，cause=$causeType")
            _playbackActivity.value = PlaybackActivity(track.id, false)
            playbackPresentation.transportStopped(playbackPresentationKey(track))
            _state.update { it.copy(playbackMessage = "播放失败，请稍后重试") }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && playerMatchesCurrentTrack() && !PlaybackSleepTimer.consumeTrackEnd()) advance(automatic = true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!playerMatchesCurrentTrack()) return
            val track = _state.value.currentTrack ?: return
            _playbackActivity.value = PlaybackActivity(track.id, isPlaying)
            if (isPlaying) reportRecentPlay(track, playbackRequestGeneration)
            if (!isPlaying) {
                val position = currentPlaybackPosition()
                updateProgress(position, track.id)
                playbackSession.save(_state.value, position)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val affectsPresentation = events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) ||
                events.contains(Player.EVENT_PLAYER_ERROR)
            if (!affectsPresentation || !playerMatchesCurrentTrack()) return
            val track = _state.value.currentTrack ?: return
            val key = playbackPresentationKey(track)
            playWhenReadyRequested = player.playWhenReady
            when {
                player.isPlaying -> playbackPresentation.present(key, true)
                !player.playWhenReady -> playbackPresentation.present(key, false)
                else -> playbackPresentation.transportStopped(key)
            }
        }
    }

    init {
        viewModelScope.launch {
            PlaybackAudioInfo.validation.collect { result ->
                if (result != null) _state.update { current ->
                    if (current.currentTrack?.previewUrl != result.first) current else {
                        val quality = qqPlaybackQuality(result.first).takeIf { result.second }
                        current.copy(activeQuality = quality,
                            availableQualities = if (result.second) verifiedPlayerQualities(quality, current.availableQualities) else emptyList(),
                            playbackMessage = if (result.second) null else "当前音源为试听片段")
                    }
                }
            }
        }
        playbackSession.whenReady { it.addListener(playerListener) }
        viewModelScope.launch {
            state.collect { current ->
                if (playerMatchesCurrentTrack()) playbackSession.remember(
                    current, playbackSession.player?.currentMediaItem?.mediaId,
                )
            }
        }
        viewModelScope.prefetchPlayerArtwork(state)
        nextAudioPreloader.observe({ _state.value }, { rapidTrackSwitch.presentation.value != null },
            { playbackSession.player })
        viewModelScope.launch {
            SystemPlaybackCommands.commands.collect { command ->
                when (command) {
                    SystemPlaybackCommand.PREVIOUS -> if (_state.value.currentTrack?.source == MusicSource.QQ) requestPrevious() else previous()
                    SystemPlaybackCommand.NEXT -> if (_state.value.currentTrack?.source == MusicSource.QQ) requestNext() else next()
                }
            }
        }
        viewModelScope.launch {
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
            _state.value.currentTrack?.let { loadLyrics(it, generation) }
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
        val existingQueue = if (track.source == MusicSource.QQ) {
            snapshot.queue.filter { it.source == MusicSource.QQ }
        } else {
            snapshot.queue
        }
        val queue = if (existingQueue.any { it.id == track.id }) {
            existingQueue
        } else {
            existingQueue.take(999) + track
        }
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        startTrack(track, queue)
    }

    fun playTrackNext(track: MusicTrack) {
        val snapshot = _state.value
        if (snapshot.currentTrack?.let { it.source == track.source && it.id == track.id } == true) {
            playTrack(track)
            return
        }
        if (snapshot.qqRadioActive) {
            val queue = qqRadio.insert(snapshot.queue, snapshot.currentTrack, track)
            playbackHistory.remember(snapshot.currentTrack?.id, track.id)
            startTrack(track, queue, preserveQqRadio = true)
            prefetchQqRadioIfNeeded()
            return
        }
        val existingQueue = if (track.source == MusicSource.QQ) {
            snapshot.queue.filter { it.source == MusicSource.QQ }
        } else {
            snapshot.queue
        }
        val queue = insertTrackAfterCurrent(existingQueue, snapshot.currentTrack, track).take(1000)
        _state.update { it.copy(queueOrder = it.queueOrder.insert(existingQueue, snapshot.currentTrack, track)) }
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        startTrack(track, queue)
    }

    fun playQqFeedTrack(track: MusicTrack) {
        val snapshot = _state.value
        if (snapshot.qqRadioActive && snapshot.currentTrack?.id == track.id) {
            playTrack(track)
            return
        }
        if (!snapshot.qqRadioActive) stopQqRadio()
        val queue = qqRadio.insert(
            if (snapshot.qqRadioActive) snapshot.queue else emptyList(),
            snapshot.currentTrack?.takeIf { it.source == MusicSource.QQ }, track,
        )
        playbackHistory.remember(snapshot.currentTrack?.id, track.id)
        _state.update { it.copy(qqRadioActive = true, shuffle = false, repeatMode = RepeatMode.ALL) }
        startTrack(track, queue, preserveQqRadio = true)
        prefetchQqRadioIfNeeded()
    }

    fun playPlaylist(playlist: MusicPlaylist, shuffle: Boolean = false) {
        _state.update { it.withPlaylistQueue(playlist.tracks, shuffle) }
        val queue = _state.value.queue
        playbackHistory.clear()
        val track = queue.firstOrNull()
        if (track == null) {
            stopQqRadio()
            _state.update { it.copy(queue = queue) }
        }
        else startTrack(track, queue)
    }

    fun togglePlay() {
        val snapshot = _state.value
        val current = snapshot.currentTrack ?: return
        if (!snapshot.isPlaying &&
            (current.previewUrl.isBlank() || !playerMatchesCurrentTrack() || playbackSession.player?.playerError != null)
        ) {
            if (!trackLoader.isResolving) {
                startTrack(
                    current,
                    snapshot.queue,
                    initialPositionMs = _progressMs.value,
                    preserveQqRadio = snapshot.qqRadioActive,
                )
            }
            return
        }
        val shouldPlay = !snapshot.isPlaying
        playWhenReadyRequested = shouldPlay
        playbackPresentation.present(playbackPresentationKey(current), shouldPlay)
        if (shouldPlay) {
            playbackSession.whenReady(Player::play)
        } else {
            playbackSession.whenReady(Player::pause)
            val position = currentPlaybackPosition()
            updateProgress(position)
            playbackSession.save(_state.value, position)
        }
    }

    fun next() {
        rapidTrackSwitch.cancel()
        advance(automatic = false)
    }

    fun requestNext() = rapidTrackSwitch.request(next = true)

    fun requestPrevious() = rapidTrackSwitch.request(next = false)

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
        val nextIndex = nextQueueIndex(index, queue.size, _state.value.shuffle, _state.value.repeatMode, automatic)
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
        val progress = (current.durationMs * fraction.coerceIn(0f, 1f)).toLong()
        updateProgress(progress, current.id)
        seekPreview.clear()
        playbackSession.seek(PlaybackRequestKey(current.id, playbackRequestGeneration), progress, ::isCurrentRequest)
        playbackSession.save(_state.value, progress)
    }

    internal fun seekToForTrack(trackId: String, fraction: Float) {
        if (_state.value.currentTrack?.id == trackId) seekTo(fraction)
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
        val replaceSource = (event is PlaybackQualityEvent.Selected && event.url != null) ||
            event is PlaybackQualityEvent.Upgraded
        val position = if (replaceSource) currentPlaybackPosition() else 0L
        _state.update { it.withQualityEvent(event) }
        if (replaceSource) _state.value.currentTrack?.let {
            startPlayback(it, position, playWhenReadyRequested, event.key.generation)
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
    ) {
        val snapshot = _state.value
        val queue = if (snapshot.qqRadioActive) {
            qqRadio.center(snapshot.queue, track.id)
        } else {
            snapshot.queue
        }
        startTrack(track, queue, preserveQqRadio = snapshot.qqRadioActive, direction = direction,
            preserveRapidSwitch = preserveRapidSwitch)
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
        startTrackFromQueue(track, direction, preserveRapidSwitch = true)
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
                .copy(queueOrder = when {
                    preserveQqRadio -> PlaybackQueueOrder()
                    queue === it.queue -> it.queueOrder
                    else -> it.queueOrder.reconcile(queue)
                })
        }
        updateProgress(startingPosition, track.id)
        playbackPresentation.present(PlaybackPresentationKey(track.id, requestGeneration), playWhenReady)
        playbackSession.resetProgressBucket(startingPosition)
        playbackSession.save(_state.value, _progressMs.value)
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
        val effective = PlaybackOptions.effective(getApplication(), preferred).playbackEquivalent()
        trackLoader.start(track, PlaybackRequestKey(track.id, requestGeneration), preferred, effective,
            initialPositionMs, playWhenReady, lyricsHandedOff)
    }

    private fun onTrackEvent(event: PlaybackTrackEvent) {
        if (!isCurrentRequest(event.key)) return
        val generation = event.key.generation
        when (event) {
            is PlaybackTrackEvent.Ready -> {
                val resolved = event.playback
                _state.update { it.withResolvedPlayback(event) }
                startPlayback(resolved.track, event.positionMs, event.playWhenReady, generation)
                qualityController.followCachedPlayback(resolved, event.key, event.effective, event.preferred)
                loadLyrics(resolved.track, generation, PLAYBACK_LYRICS_DELAY_MS)
                loadQualityOptions(resolved.track, generation, PLAYBACK_QUALITY_DELAY_MS)
            }
            is PlaybackTrackEvent.Metadata -> {
                _state.update { it.withPlaybackMetadata(event) }
                val updated = _state.value.currentTrack ?: return
                // 信息流补齐身份后重试档位探测和已播上报，保留当前音源与歌词。
                if (event.original.durationMs <= 0L || updated.qualityIds.isEmpty()) loadQualityOptions(updated, generation)
                if (playbackSession.player?.isPlaying == true) reportRecentPlay(updated, generation)
            }
            is PlaybackTrackEvent.Failed -> {
                playWhenReadyRequested = false
                playbackPresentation.transportStopped(PlaybackPresentationKey(event.key.trackId, generation))
                _state.update { it.copy(qualityLoading = false, lyricLoadState = LyricLoadState.UNAVAILABLE,
                    qualityChanging = false, playbackMessage = event.message) }
            }
        }
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

    private fun startPlayback(track: MusicTrack, positionMs: Long = 0L, playWhenReady: Boolean = true,
                              requestGeneration: Long = playbackRequestGeneration) {
        playbackSession.start(track, PlaybackRequestKey(track.id, requestGeneration), positionMs, playWhenReady,
            ::isCurrentRequest, started = { player, startingPosition ->
                playbackSession.remember(_state.value, track.id)
                updateProgress(startingPosition, track.id)
                _playbackActivity.value = PlaybackActivity(track.id, player.isPlaying)
            }, failed = {
                playWhenReadyRequested = false
                playbackPresentation.transportStopped(PlaybackPresentationKey(track.id, requestGeneration))
            })
    }

    private fun updateProgress(positionMs: Long, trackId: String? = _state.value.currentTrack?.id) {
        val position = positionMs.coerceAtLeast(0L)
        _progressMs.value = position
        _playbackProgress.value = PlaybackProgressSnapshot(trackId, position)
    }

    private fun reportRecentPlay(track: MusicTrack, generation: Long) = recentPlayReporter.report(track, generation)

    private fun playbackPresentationKey(track: MusicTrack): PlaybackPresentationKey =
        PlaybackPresentationKey(track.id, playbackRequestGeneration)

    override fun onCleared() {
        nextAudioPreloader.stop()
        playbackPresentation.cancel()
        val position = currentPlaybackPosition()
        val snapshot = _state.value
        cancelPendingTrackWork()
        qqRadio.stop()
        playbackSession.close(snapshot, position, playerListener)
    }

}
