package com.musicone.nativeaudio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@OptIn(UnstableApi::class)
class NativeAudioMediaSource internal constructor(
    private val mediaItem: MediaItem,
    private val dataSourceFactory: DataSource.Factory,
    private val targetDsdSampleRate: Int = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
    private val context: Context? = null,
) : BaseMediaSource() {

    private var durationUs: Long = C.TIME_UNSET
    private var timelineIsPlaceholder = true

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        // 先发布一个准备态 Timeline
        notifyTimelineChanged(durationUs, isSeekable = true)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
        val uri = mediaItem.localConfiguration?.uri ?: error("MediaItem does not have URI")
        val dataSpec = DataSpec.Builder().setUri(uri).build()
        val period = NativeAudioMediaPeriod(
            context = context,
            dataSourceFactory = dataSourceFactory,
            dataSpec = dataSpec,
            targetDsdSampleRate = targetDsdSampleRate,
            onDurationDetermined = { determinedDurationUs ->
                if (determinedDurationUs > 0 && determinedDurationUs != durationUs) {
                    notifyTimelineChanged(determinedDurationUs, isSeekable = true)
                }
            },
        )
        return period
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        if (mediaPeriod is NativeAudioMediaPeriod) {
            mediaPeriod.release()
        }
    }

    override fun releaseSourceInternal() {
        // 释放 source 资源
    }

    override fun maybeThrowSourceInfoRefreshError() {}

    private fun notifyTimelineChanged(durationUs: Long, isSeekable: Boolean) {
        this.durationUs = durationUs
        this.timelineIsPlaceholder = false
        val timeline = SinglePeriodTimeline(
            durationUs,
            isSeekable,
            /* isDynamic = */ false,
            /* useDefaultPosition = */ false,
            /* manifest = */ null,
            mediaItem,
        )
        refreshSourceInfo(timeline)
    }

    class Factory(
        private val dataSourceFactory: DataSource.Factory,
        private val targetDsdSampleRate: Int = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
        private val context: Context? = null,
    ) : MediaSourceFactory {

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = this

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = this

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            return NativeAudioMediaSource(
                mediaItem = mediaItem,
                dataSourceFactory = dataSourceFactory,
                targetDsdSampleRate = targetDsdSampleRate,
                context = context,
            )
        }
    }
}
