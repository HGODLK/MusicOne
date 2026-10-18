package com.musicone.demo

import android.os.Process
import androidx.media3.common.C
import com.decent.usbaudio.UsbAudioStream
import com.musicone.nativeaudio.NativePcmResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/** USB 写线程与 Media3 渲染线程解耦，由有界队列提供背压。 */
internal class DirectUsbStreamingThread(
    private val stream: UsbAudioStream,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val outputSampleRate: Int = sampleRate,
    private val resampler: NativePcmResampler? = null,
    private val volumeGain: () -> Float,
) {
    private data class AudioBuffer(val data: ByteArray, val encoding: Int, val validBitDepth: Int)

    private val integerBuffers = ConcurrentLinkedQueue<ByteArray>()
    private var floatScratch = FloatArray(0)
    private val diagnostics = UsbPlaybackDiagnostics(outputSampleRate)
    private val worker: UsbOutputWorker<AudioBuffer> = UsbOutputWorker(
        transport = object : UsbOutputTransport {
            override val completedFrames get() = stream.completedFrames
            override fun interrupt() = stream.stop()
            override fun flush() { resampler?.reset(); stream.flush(); diagnostics.reset() }
            override fun pollPending() = stream.pollPending()
            override fun finish() {
                if (resampler != null) {
                    while (true) {
                        val tail = resampler.drain(volumeGain().toDouble())
                        if (tail.isEmpty()) break
                        stream.writeRaw(tail, C.ENCODING_PCM_32BIT, 32)
                    }
                }
                stream.finish()
            }
        },
        write = { buffer ->
            val started = System.nanoTime()
            if (resampler == null) writePcm(buffer.data, buffer.encoding, buffer.validBitDepth)
            else {
                val converted = resampler.convert(buffer.data, volumeGain().toDouble())
                if (converted.isNotEmpty()) stream.writeRaw(converted, C.ENCODING_PCM_32BIT, 32)
            }
            diagnostics.written(started, System.nanoTime(),
                bufferedFrames() * outputSampleRate / sampleRate, stream.pendingAudioFrames)
        },
        recycle = { buffer -> integerBuffers.offer(buffer.data) },
        initializeThread = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
        bufferFrames = { buffer ->
            buffer.data.size.toLong() / (channelCount * bytesPerSample(buffer.encoding))
        },
        // 软件队列按时长限流，原生在途请求另有约 80ms 容量。
        maxBufferedFrames = sampleRate.toLong() * 200 / 1000,
    )

    fun start() = worker.start()

    fun enqueueFloat(source: ByteBuffer): Boolean = enqueueInteger(source, C.ENCODING_PCM_FLOAT, 32)

    fun enqueueInteger(source: ByteBuffer, encoding: Int, validBitDepth: Int): Boolean {
        val size = source.remaining()
        if (!worker.canAccept(size.toLong() / (channelCount * bytesPerSample(encoding)))) return false
        val data = integerBuffers.poll()?.takeIf { it.size == size } ?: ByteArray(size)
        source.get(data)
        return worker.enqueue(AudioBuffer(data, encoding, validBitDepth)).also { accepted ->
            if (!accepted) integerBuffers.offer(data)
        }
    }

    val completedFrames get() = stream.completedFrames
    private fun bufferedFrames() = worker.bufferedFrames()
    private fun bytesPerSample(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> error("不支持的 USB PCM 编码")
    }
    fun queueSize() = worker.queueSize()
    fun hasPendingData() = worker.hasPendingData()
    fun isEnded() = worker.isEnded()
    fun error() = worker.error()
    fun finish() = worker.finish()
    fun pause() { diagnostics.invalidate(); worker.pause() }
    fun resume() = worker.resume()
    fun flush() = worker.flush()

    fun stop() {
        worker.stop()
        resampler?.close()
        integerBuffers.clear()
    }

    private fun writePcm(bytes: ByteArray, encoding: Int, validBitDepth: Int) {
        if (bytes.isEmpty()) return
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val size = bytes.size / 4
            if (floatScratch.size != size) floatScratch = FloatArray(size)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatScratch)
            PcmSoftwareGain.apply(floatScratch, volumeGain())
            stream.write(floatScratch)
        } else {
            PcmSoftwareGain.apply(bytes, encoding, volumeGain())
            stream.writeRaw(bytes, encoding, validBitDepth)
        }
    }
}
