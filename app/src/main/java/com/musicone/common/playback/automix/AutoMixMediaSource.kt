package com.musicone

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 自然过渡排进播放列表的 B：自动接续时从 startUs 开始（默认位置），而不是从头。
 * serial 区分每次排程：上一次过渡留下的条目同样带这个标记，识别 B 时必须比对本次的 serial，不能只看类型。
 */
internal data class AutoMixStartTag(val startUs: Long, val serial: Long)

/**
 * 包在播放器媒体源工厂外层：
 * 1. 带 AutoMixStartTag 的条目改写默认起播位置；
 * 2. 只换 mediaId / 歌曲信息（地址、缓存键不变）时总允许原地更新，交接时改名不会重建播放源。
 * 其余行为原样透传。
 */
@OptIn(UnstableApi::class)
internal class AutoMixMediaSourceFactory(private val delegate: MediaSource.Factory) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        AutoMixItemSource(delegate.createMediaSource(mediaItem), mediaItem)
}

@OptIn(UnstableApi::class)
private class AutoMixItemSource(source: MediaSource, initial: MediaItem) : WrappingMediaSource(source) {
    @Volatile private var override: MediaItem? = null
    private val startUs = (initial.localConfiguration?.tag as? AutoMixStartTag)?.startUs

    override fun getMediaItem(): MediaItem = override ?: super.getMediaItem()

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean =
        super.canUpdateMediaItem(mediaItem) || mediaItem.localConfiguration == getMediaItem().localConfiguration

    override fun updateMediaItem(mediaItem: MediaItem) {
        if (super.canUpdateMediaItem(mediaItem)) { override = null; super.updateMediaItem(mediaItem) }
        else override = mediaItem
    }

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        val start = startUs
        refreshSourceInfo(if (start == null) newTimeline else StartTimeline(newTimeline, start))
    }

    private class StartTimeline(timeline: Timeline, private val startUs: Long) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            if (!window.isPlaceholder && (window.durationUs == androidx.media3.common.C.TIME_UNSET || startUs < window.durationUs)) {
                window.defaultPositionUs = startUs
            }
            return window
        }
    }
}

/** 构造自然过渡和离线解码用的 MediaItem（配置与正常起播一致，但不改当前活跃缓存键）。 */
@OptIn(UnstableApi::class)
internal fun autoMixMediaItem(context: Context, track: MusicTrack, mediaId: String = track.id,
                              metadataTrack: MusicTrack = track, startTag: AutoMixStartTag? = null): MediaItem {
    val item = MediaItem.Builder().setMediaId(mediaId).setUri(track.previewUrl).setMediaMetadata(systemMediaMetadata(metadataTrack))
    if (track.previewUrl.startsWith("data:application/dash+xml")) item.setMimeType("application/dash+xml")
    else if (track.source != MusicSource.LOCAL) item.setCustomCacheKey(playbackCacheKey(context, track))
    SupplementalPlaybackRequests.resource(track.previewUrl)?.mimeType?.let(item::setMimeType)
    if (startTag != null) item.setTag(startTag)
    return item.build()
}

/** 把磁盘缓存的活跃 key 切到当前歌曲，防止正在播的缓存被清理掉。 */
internal fun markAutoMixActiveCacheKey(context: Context, track: MusicTrack) {
    MusicDiskCache.available()?.activeKey = when {
        track.previewUrl.startsWith("data:application/dash+xml") -> SupplementalPlaybackRequests.cacheKey(track.previewUrl)
        track.source != MusicSource.LOCAL -> playbackCacheKey(context, track)
        else -> null
    }
}
