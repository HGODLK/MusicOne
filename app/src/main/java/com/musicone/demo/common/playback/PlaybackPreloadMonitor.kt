package com.musicone.demo

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/** 在真实播放器和当前媒体周期边界收集证据；不依赖页面播放动画或音源预设码率。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackPreloadMonitor(
    val timing: PlaybackPreloadTiming,
    private val state: () -> MusicOneUiState,
    private val switching: () -> Boolean,
    private val connected: () -> Boolean,
) : Player.Listener, AnalyticsListener {
    private var player: ExoPlayer? = null
    private var bandwidth: PlaybackPreloadBandwidth? = null
    private var failureRevision = 0L

    fun attach(transport: Player, measurements: PlaybackPreloadBandwidth) {
        close()
        player = transport as? ExoPlayer
        bandwidth = measurements
        failureRevision = measurements.failureRevision
        timing.newTrack()
        player?.addListener(this)
        player?.addAnalyticsListener(this)
        refresh()
    }

    fun foregroundChanged(visible: Boolean): Boolean {
        val reset = timing.foregroundChanged(visible)
        if (reset) { bandwidth?.resetMeasurements(); refresh() }
        return reset
    }

    override fun onEvents(player: Player, events: Player.Events) { refresh() }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo,
        reason: Int) {
        timing.newTrack()
        bandwidth?.resetMeasurements()
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        timing.newTrack()
        bandwidth?.newMedia()
    }

    private fun current(event: AnalyticsListener.EventTime): Boolean {
        val transport = player ?: return false
        if (event.windowIndex !in 0 until event.timeline.windowCount) return false
        val item = event.timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem
        val current = transport.currentMediaItem ?: return false
        if (item.mediaId != current.mediaId || item.localConfiguration?.uri != current.localConfiguration?.uri) return false
        val period = event.mediaPeriodId ?: return false
        return transport.currentPeriodIndex in 0 until transport.currentTimeline.periodCount &&
            transport.currentTimeline.getUidOfPeriod(transport.currentPeriodIndex) == period.periodUid
    }

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData, retryCount: Int) {
        if (current(eventTime) && mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
            mediaLoadData.trackType in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_UNKNOWN)) {
            bandwidth?.mediaLoad(loadEventInfo.loadTaskId, loadEventInfo.dataSpec.uri.toString())
        }
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData) { bandwidth?.finishMediaLoad(loadEventInfo.loadTaskId) }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData) {
        bandwidth?.finishMediaLoad(loadEventInfo.loadTaskId)
        if (current(eventTime)) timing.interrupt()
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        bandwidth?.finishMediaLoad(loadEventInfo.loadTaskId)
        if (current(eventTime)) timing.interrupt()
    }

    fun refresh() {
        val transport = player ?: return
        val measurements = bandwidth ?: return
        val snapshot = state()
        val current = snapshot.currentTrack
        val eligible = current != null && transport.currentMediaItem?.mediaId == current.id &&
            !snapshot.qualityChanging && !switching() && transport.playWhenReady &&
            transport.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            transport.playerError == null && connected()
        val revision = measurements.failureRevision
        val bitrate = transport.audioFormat?.averageBitrate?.takeIf { it > 0 } ?: 0
        val now = System.nanoTime()
        val insufficient = transport.isLoading && transport.totalBufferedDuration in 0L..250L &&
            measurements.insufficient(now, (bitrate * transport.playbackParameters.speed).toLong())
        timing.observe(now / 1_000_000L, transport.isPlaying,
            transport.playbackState == Player.STATE_BUFFERING, eligible, insufficient, revision != failureRevision)
        failureRevision = revision
    }

    fun close() {
        player?.removeListener(this)
        player?.removeAnalyticsListener(this)
        player = null
        bandwidth = null
        timing.newTrack()
    }
}
