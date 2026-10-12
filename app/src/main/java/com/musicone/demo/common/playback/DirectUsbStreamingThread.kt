package com.musicone.demo

import android.os.Process
import androidx.media3.common.C
import com.decent.usbaudio.UsbAudioStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/** USB 写线程与 Media3 渲染线程解耦，由有界队列提供背压。 */
internal class DirectUsbStreamingThread(
    private val stream: UsbAudioStream,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val volumeGain: () -> Float,
) {
    private sealed interface AudioBuffer {
        data class FloatPcm(val data: FloatArray) : AudioBuffer
        data class IntegerPcm(
            val data: ByteArray,
            val encoding: Int,
            val validBitDepth: Int,
        ) : AudioBuffer
    }

    private val floatBuffers = ConcurrentLinkedQueue<FloatArray>()
    private val integerBuffers = ConcurrentLinkedQueue<ByteArray>()
    private val diagnostics = UsbPlaybackDiagnostics(sampleRate)
    private val worker: UsbOutputWorker<AudioBuffer> = UsbOutputWorker(
        transport = object : UsbOutputTransport {
            override val completedFrames get() = stream.completedFrames
            override fun interrupt() = stream.stop()
            override fun flush() { stream.flush(); diagnostics.reset() }
            override fun pollPending() = stream.pollPending()
            override fun finish() = stream.finish()
        },
        write = { buffer ->
            val started = System.nanoTime()
            when (buffer) {
                is AudioBuffer.FloatPcm -> {
                    PcmSoftwareGain.apply(buffer.data, volumeGain())
                    stream.write(buffer.data)
                }
                is AudioBuffer.IntegerPcm -> {
                    PcmSoftwareGain.apply(buffer.data, buffer.encoding, volumeGain())
                    stream.writeRaw(buffer.data, buffer.encoding, buffer.validBitDepth)
                }
            }
            diagnostics.written(started, System.nanoTime(), bufferedFrames(), stream.pendingAudioFrames)
        },
        recycle = { buffer ->
            when (buffer) {
                is AudioBuffer.FloatPcm -> floatBuffers.offer(buffer.data)
                is AudioBuffer.IntegerPcm -> integerBuffers.offer(buffer.data)
            }
        },
        initializeThread = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
        bufferFrames = { buffer ->
            when (buffer) {
                is AudioBuffer.FloatPcm -> buffer.data.size.toLong() / channelCount
                is AudioBuffer.IntegerPcm -> buffer.data.size.toLong() / (channelCount * bytesPerSample(buffer.encoding))
            }
        },
        // 软件队列按时长限流，原生在途请求另有约 80ms 容量。
        maxBufferedFrames = sampleRate.toLong() * 200 / 1000,
    )

    fun start() = worker.start()

    fun enqueueFloat(source: ByteBuffer): Boolean {
        val size = source.remaining() / Float.SIZE_BYTES
        if (!worker.canAccept(size.toLong() / channelCount)) return false
        val data = floatBuffers.poll()?.takeIf { it.size == size } ?: FloatArray(size)
        source.asFloatBuffer().get(data)
        return worker.enqueue(AudioBuffer.FloatPcm(data)).also { accepted ->
            if (!accepted) floatBuffers.offer(data)
        }
    }

    fun enqueueInteger(source: ByteBuffer, encoding: Int, validBitDepth: Int): Boolean {
        val size = source.remaining()
        if (!worker.canAccept(size.toLong() / (channelCount * bytesPerSample(encoding)))) return false
        val data = integerBuffers.poll()?.takeIf { it.size == size } ?: ByteArray(size)
        source.get(data)
        return worker.enqueue(AudioBuffer.IntegerPcm(data, encoding, validBitDepth)).also { accepted ->
            if (!accepted) integerBuffers.offer(data)
        }
    }

    val completedFrames get() = stream.completedFrames
    private fun bufferedFrames() = worker.bufferedFrames()
    private fun bytesPerSample(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
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
        floatBuffers.clear()
        integerBuffers.clear()
    }
}
