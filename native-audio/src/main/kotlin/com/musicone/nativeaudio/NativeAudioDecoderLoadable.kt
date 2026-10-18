package com.musicone.nativeaudio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.Loader
import java.io.IOException

/** 单轮解码只产出有界结果，交给 Loader 回调在播放线程发布。 */
@OptIn(UnstableApi::class)
internal class NativeAudioDecoderLoadable(
    private val context: Context?,
    private val dataSourceFactory: DataSource.Factory,
    private val dataSpec: DataSpec,
    private val targetDsdSampleRate: Int,
    private val requestedSeekUs: Long?,
    val generation: Int,
    existingSession: NativeAudioSession?,
    initialEndPositionUs: Long,
    private val maxBytes: Int,
) : Loader.Loadable {
    @Volatile private var isCancelled = false
    @Volatile var session: NativeAudioSession? = existingSession
        private set
    var sourceInfo: NativeAudioSourceInfo? = null
        private set
    val chunks = ArrayList<PcmChunk>()
    var endPositionUs = initialEndPositionUs
        private set
    var reachedEndOfStream = false
        private set

    override fun cancelLoad() {
        isCancelled = true
        session?.cancel()
    }

    override fun load() {
        try {
            val decoder = session ?: NativeAudioSession(
                dataSourceFactory.createDataSource(), dataSpec, targetDsdSampleRate, context,
            ).also { session = it; it.open() }
            sourceInfo = decoder.format
            if (isCancelled) return
            if (requestedSeekUs != null && !decoder.seekTo(requestedSeekUs)) {
                throw IOException("原生音频无法跳转到指定位置")
            }
            if (isCancelled) return
            decodeRound(decoder)
        } catch (error: Exception) {
            if (!isCancelled) throw IOException("原生音频解码失败：${error.message}", error)
        }
    }

    private fun decodeRound(decoder: NativeAudioSession) {
        val info = decoder.format
        val bytesPerFrame = info.outputBytesPerFrame
        val roundBytes = minOf(maxBytes.toLong(), info.outputSampleRate.toLong() * bytesPerFrame)
            .coerceAtLeast(bytesPerFrame.toLong()).toInt()
        var loadedBytes = 0
        var emptyReads = 0
        val pts = LongArray(1)
        while (!isCancelled && loadedBytes + bytesPerFrame <= roundBytes) {
            val buffer = decoder.readNextPcmChunk(pts, roundBytes - loadedBytes)
            if (isCancelled) return
            if (buffer == null) {
                reachedEndOfStream = true
                chunks.add(PcmChunk(ByteArray(0), endPositionUs, isEndOfStream = true))
                return
            }
            if (!buffer.hasRemaining()) {
                if (++emptyReads > 10) throw IOException("原生音频连续读取不到有效 PCM 数据")
                continue
            }
            emptyReads = 0
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val durationUs = bytes.size.toLong() / bytesPerFrame * 1_000_000L / info.outputSampleRate
            val chunk = PcmChunk(bytes, pts[0], durationUs = durationUs)
            chunks.add(chunk)
            endPositionUs = chunk.endTimeUs
            loadedBytes += bytes.size
        }
    }
}
