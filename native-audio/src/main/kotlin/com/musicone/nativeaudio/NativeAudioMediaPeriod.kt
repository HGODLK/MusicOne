package com.musicone.nativeaudio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Loader
import java.io.IOException

@OptIn(UnstableApi::class)
internal class NativeAudioMediaPeriod(
    private val context: Context? = null,
    private val dataSourceFactory: DataSource.Factory,
    private val dataSpec: DataSpec,
    private val targetDsdSampleRate: Int,
    private val onDurationDetermined: (Long) -> Unit = {},
) : MediaPeriod, Loader.Callback<NativeAudioDecoderLoadable> {

    private var callback: MediaPeriod.Callback? = null
    private var session: NativeAudioSession? = null
    private var activeLoadable: NativeAudioDecoderLoadable? = null
    private var sampleStream: NativeAudioSampleStream? = null
    private var trackGroupArray = TrackGroupArray.EMPTY
    private var sourceInfo: NativeAudioSourceInfo? = null
    private val loader = Loader("NativeAudioDecoder")
    private var seekGeneration = 0
    private var currentPositionUs = 0L
    private var bufferedPositionUs = 0L
    private var isDisposed = false
    private var prepareError: IOException? = null
    private var isEndOfStreamReached = false
    private var pendingSeekPositionUs: Long? = null
    private var loadingRequestPending = false
    private var targetBufferDurationUs = MAX_BUFFER_DURATION_US
    private val targetBufferBytes: Long
        get() = maxOf(MAX_BUFFER_BYTES, sourceInfo?.let {
            targetBufferDurationUs * it.outputSampleRate / 1_000_000L * it.outputBytesPerFrame
        } ?: MAX_BUFFER_BYTES)

    fun getSourceInfo(): NativeAudioSourceInfo? = sourceInfo

    override fun maybeThrowPrepareError() {
        prepareError?.let { throw it }
        loader.maybeThrowError()
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        currentPositionUs = positionUs
        bufferedPositionUs = positionUs
        startDecoderLoadable(positionUs.takeIf { it > 0 })
    }

    private fun publishSession(loadable: NativeAudioDecoderLoadable) {
        val info = loadable.sourceInfo ?: return
        session = loadable.session
        if (sourceInfo != null) return
        sourceInfo = info
        val effectiveBitDepth = if (info.isDsd) 32 else info.sourceBitDepth
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(info.outputEncoding)
            .setSampleRate(info.outputSampleRate)
            .setChannelCount(info.outputChannels)
            .setAverageBitrate(info.outputSampleRate * info.outputBytesPerFrame * 8)
            .setId("native-pcm")
            .setLabel("effective-depth:$effectiveBitDepth")
            .build()
        trackGroupArray = TrackGroupArray(TrackGroup("audio", format))
        sampleStream = NativeAudioSampleStream(format, this)
        if (info.durationUs > 0) onDurationDetermined(info.durationUs)
        callback?.onPrepared(this)
    }

    override fun getTrackGroups(): TrackGroupArray = trackGroupArray

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        for (i in selections.indices) {
            if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                streams[i] = null
            }
            if (streams[i] == null && selections[i] != null) {
                streams[i] = sampleStream
                streamResetFlags[i] = true
            }
        }
        currentPositionUs = positionUs
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        // 队列只保存尚未交付给渲染器的样本，不能把仍需播放的整块 PCM 当历史缓冲丢弃。
        currentPositionUs = positionUs
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long {
        currentPositionUs = positionUs
        bufferedPositionUs = positionUs
        isEndOfStreamReached = false
        seekGeneration++
        sampleStream?.clearQueue()
        if (loader.isLoading) {
            // 取消完成后只启动最后一次跳转，所有队列写入均由播放线程处理。
            pendingSeekPositionUs = positionUs
            loader.cancelLoading()
        } else {
            pendingSeekPositionUs = null
            startDecoderLoadable(positionUs)
        }
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long =
        if (isEndOfStreamReached) C.TIME_END_OF_SOURCE else bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = getBufferedPositionUs()

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        loadingRequestPending = false
        currentPositionUs = loadingInfo.playbackPositionUs
        targetBufferDurationUs = (MAX_BUFFER_DURATION_US * loadingInfo.playbackSpeed.coerceAtLeast(1f)).toLong()
        if (isDisposed || prepareError != null || isEndOfStreamReached || pendingSeekPositionUs != null) return false
        if (loader.isLoading) return true
        val stream = sampleStream ?: return false
        if (bufferedPositionUs - currentPositionUs >= targetBufferDurationUs || stream.queuedBytes() >= targetBufferBytes) {
            return false
        }
        // 顺序续播沿用解码器当前位置，只有用户跳转才携带目标时间（包括 0 秒）。
        startDecoderLoadable(null)
        return true
    }

    override fun isLoading(): Boolean = loader.isLoading

    override fun reevaluateBuffer(positionUs: Long) {
        currentPositionUs = positionUs
        // Media3 仍有加载意图时不会重复调用 continueLoading，消费到低水位后需主动唤醒。
        if (!isDisposed && prepareError == null && !isEndOfStreamReached && pendingSeekPositionUs == null &&
            !loader.isLoading && !loadingRequestPending && sampleStream != null &&
            bufferedPositionUs - positionUs < targetBufferDurationUs / 2 &&
            sampleStream!!.queuedBytes() < targetBufferBytes
        ) {
            loadingRequestPending = true
            callback?.onContinueLoadingRequested(this)
        }
    }

    private fun startDecoderLoadable(seekUs: Long?) {
        if (isDisposed) return
        val availableBytes = minOf(MAX_BUFFER_BYTES, (targetBufferBytes - (sampleStream?.queuedBytes() ?: 0L)).coerceAtLeast(0L))
        val info = sourceInfo
        val availableDurationUs = (targetBufferDurationUs - (bufferedPositionUs - currentPositionUs).coerceAtLeast(0L))
            .coerceAtLeast(0L)
        val durationBytes = if (info == null) MAX_BUFFER_BYTES else
            availableDurationUs * info.outputSampleRate / 1_000_000L * info.outputBytesPerFrame
        val loadable = NativeAudioDecoderLoadable(
            context, dataSourceFactory, dataSpec, targetDsdSampleRate,
            seekUs, seekGeneration, session, bufferedPositionUs,
            minOf(availableBytes, durationBytes).coerceAtLeast((info?.outputBytesPerFrame ?: 1).toLong()).toInt(),
        )
        activeLoadable = loadable
        loader.startLoading(loadable, this, 0)
    }

    fun release() {
        isDisposed = true
        seekGeneration++
        sampleStream?.clearQueue()
        val lastLoadable = activeLoadable
        val lastSession = session
        // 等后台读取真正退出后再释放句柄，避免关闭与 JNI 解码并发。
        loader.release {
            lastLoadable?.session?.close()
            if (lastSession !== lastLoadable?.session) lastSession?.close()
        }
    }

    override fun onLoadCompleted(loadable: NativeAudioDecoderLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long) {
        if (isDisposed) return
        publishSession(loadable)
        if (loadable.generation == seekGeneration) {
            for (chunk in loadable.chunks) sampleStream?.queueChunk(chunk)
            bufferedPositionUs = loadable.endPositionUs
            isEndOfStreamReached = loadable.reachedEndOfStream
        }
        resumeLoading()
    }

    override fun onLoadCanceled(loadable: NativeAudioDecoderLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean) {
        if (released || isDisposed) return
        if (loadable.sourceInfo == null) loadable.session?.close()
        else publishSession(loadable)
        resumeLoading()
    }

    private fun resumeLoading() {
        val pending = pendingSeekPositionUs
        pendingSeekPositionUs = null
        if (pending != null) startDecoderLoadable(pending)
        else callback?.onContinueLoadingRequested(this)
    }

    override fun onLoadError(
        loadable: NativeAudioDecoderLoadable,
        elapsedRealtimeMs: Long,
        loadDurationMs: Long,
        error: IOException,
        errorCount: Int,
    ): Loader.LoadErrorAction {
        prepareError = error
        return Loader.DONT_RETRY_FATAL
    }

    private companion object {
        const val MAX_BUFFER_DURATION_US = 1_200_000L
        const val MAX_BUFFER_BYTES = 1024 * 1024L
    }
}
