package com.musicone.demo

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** DASH 清单不一定包含采样率，首次输入格式就绪时继续发布实际规格。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackAudioMetadataListener(private val player: Player) : Player.Listener, AnalyticsListener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        PlaybackAudioInfo.activate(mediaItem?.localConfiguration?.uri?.toString())
    }

    override fun onTracksChanged(tracks: Tracks) {
        val url = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return
        val index = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return
        publish(url, group.getTrackFormat(index))
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?) {
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return
        val item = timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val current = player.currentMediaItem ?: return
        if (item.mediaId != current.mediaId || item.localConfiguration?.uri != current.localConfiguration?.uri) return
        // 同一首歌重播也有独立媒体周期，旧周期的格式不能污染新播放轮次。
        eventTime.mediaPeriodId?.let { period ->
            val currentTimeline = player.currentTimeline
            if (player.currentPeriodIndex !in 0 until currentTimeline.periodCount ||
                currentTimeline.getUidOfPeriod(player.currentPeriodIndex) != period.periodUid) return
        }
        val url = current.localConfiguration?.uri?.toString() ?: return
        publish(url, format)
    }

    private fun publish(url: String, format: Format) {
        val flacDepth = if (format.sampleMimeType == "audio/flac") {
            format.initializationData.firstNotNullOfOrNull { qqFlacInfo(it)?.bitDepth }
        } else null
        val pcmDepth = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        PlaybackAudioInfo.publish(url, format.sampleMimeType, format.sampleRate, format.bitrate,
            flacDepth ?: pcmDepth)
    }
}
