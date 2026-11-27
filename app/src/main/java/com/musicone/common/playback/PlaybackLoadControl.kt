package com.musicone

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/** 在线歌曲的待播缓冲上限按整首歌放宽，播放器会一路读到文件末尾。 */
internal const val PLAYBACK_FULL_TRACK_BUFFER_MS = 20 * 60_000
/** 内存里待播数据的封顶；CD 音质 FLAC 约七八分钟以内可整首读完，更高码率超出部分照常边播边读。 */
internal const val PLAYBACK_FULL_TRACK_BUFFER_BYTES = 64 * 1024 * 1024

/**
 * 当前播放倾向一次缓存整首：读到文件末尾后磁盘缓存就是完整文件，换音质、断网续播和后续的自然过渡分析都不必再等网络。
 * 不能另起写入器去补：播放器的读取连接在缓冲暂停时仍持有同一段缓存的写锁，写入器会一直等到这首播完。
 * 起播门槛沿用默认值，起播速度不变；本地文件（file/content 等）按默认本地参数，不整首读进内存。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackLoadControl(): LoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMsForStreaming(
        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        PLAYBACK_FULL_TRACK_BUFFER_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    )
    .setTargetBufferBytes(PLAYBACK_FULL_TRACK_BUFFER_BYTES)
    .build()
