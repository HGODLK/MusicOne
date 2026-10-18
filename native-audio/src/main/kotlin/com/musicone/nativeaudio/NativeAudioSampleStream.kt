package com.musicone.nativeaudio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 封装单个 PCM 缓冲块。
 */
internal data class PcmChunk(
    val data: ByteArray,
    val timeUs: Long,
    val isEndOfStream: Boolean = false,
    val durationUs: Long = 0L,
) {
    val endTimeUs: Long get() = timeUs + durationUs
}

/**
 * 实现 Media3 SampleStream，向音频渲染管线提供 audio/raw PCM 数据。
 */
@OptIn(UnstableApi::class)
internal class NativeAudioSampleStream(
    private val format: Format,
    private val period: NativeAudioMediaPeriod,
) : SampleStream {

    private val queue = ConcurrentLinkedQueue<PcmChunk>()
    private var formatSent = false
    @Volatile private var isEndOfStreamQueued = false

    fun queueChunk(chunk: PcmChunk) {
        if (chunk.isEndOfStream) {
            isEndOfStreamQueued = true
        }
        queue.add(chunk)
    }

    fun clearQueue() {
        queue.clear()
        isEndOfStreamQueued = false
    }

    fun queuedDurationUs(currentPositionUs: Long): Long {
        val last = queue.lastOrNull() ?: return 0L
        return (last.endTimeUs - currentPositionUs).coerceAtLeast(0L)
    }

    fun queuedBytes(): Long {
        var bytes = 0L
        for (c in queue) {
            bytes += c.data.size
        }
        return bytes
    }

    override fun isReady(): Boolean {
        return queue.isNotEmpty() || isEndOfStreamQueued
    }

    @Throws(IOException::class)
    override fun maybeThrowError() {
        period.maybeThrowPrepareError()
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        val formatRequired = (readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0
        if (!formatSent || formatRequired) {
            formatHolder.format = format
            formatSent = true
            return C.RESULT_FORMAT_READ
        }

        val peekOnly = (readFlags and SampleStream.FLAG_PEEK) != 0
        val chunk = queue.peek() ?: return C.RESULT_NOTHING_READ

        if (chunk.isEndOfStream) {
            buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            buffer.timeUs = chunk.timeUs
            return C.RESULT_BUFFER_READ
        }
        if (!peekOnly) queue.poll()

        val flagsOnly = (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) != 0
        buffer.timeUs = chunk.timeUs
        buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)

        if (!flagsOnly) {
            buffer.ensureSpaceForWrite(chunk.data.size)
            val byteBuffer: ByteBuffer? = buffer.data
            byteBuffer?.put(chunk.data)
        }

        return C.RESULT_BUFFER_READ
    }

    override fun skipData(positionUs: Long): Int {
        var skipped = 0
        while (true) {
            val peek = queue.peek() ?: break
            if (peek.endTimeUs <= positionUs && !peek.isEndOfStream) {
                queue.poll()
                skipped++
            } else {
                break
            }
        }
        return skipped
    }
}
