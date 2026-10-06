package com.musicone.demo

import android.os.Process
import com.decent.usbaudio.UsbAudioStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/** USB 写线程与 Media3 渲染线程解耦，由有界队列提供背压。 */
internal class DirectUsbStreamingThread(
    private val stream: UsbAudioStream,
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
    private val worker = UsbOutputWorker<AudioBuffer>(
        transport = object : UsbOutputTransport {
            override val completedFrames get() = stream.completedFrames
            override fun interrupt() = stream.stop()
            override fun flush() = stream.flush()
            override fun pollPending() = stream.pollPending()
            override fun finish() = stream.finish()
        },
        write = { buffer ->
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
        },
        recycle = { buffer ->
            when (buffer) {
                is AudioBuffer.FloatPcm -> floatBuffers.offer(buffer.data)
                is AudioBuffer.IntegerPcm -> integerBuffers.offer(buffer.data)
            }
        },
        initializeThread = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
    )

    fun start() = worker.start()

    fun enqueueFloat(source: ByteBuffer): Boolean {
        val size = source.remaining() / Float.SIZE_BYTES
        val data = floatBuffers.poll()?.takeIf { it.size == size } ?: FloatArray(size)
        source.asFloatBuffer().get(data)
        return worker.enqueue(AudioBuffer.FloatPcm(data)).also { accepted ->
            if (!accepted) floatBuffers.offer(data)
        }
    }

    fun enqueueInteger(source: ByteBuffer, encoding: Int, validBitDepth: Int): Boolean {
        val size = source.remaining()
        val data = integerBuffers.poll()?.takeIf { it.size == size } ?: ByteArray(size)
        source.get(data)
        return worker.enqueue(AudioBuffer.IntegerPcm(data, encoding, validBitDepth)).also { accepted ->
            if (!accepted) integerBuffers.offer(data)
        }
    }

    val completedFrames get() = stream.completedFrames
    fun queueSize() = worker.queueSize()
    fun hasPendingData() = worker.hasPendingData()
    fun isEnded() = worker.isEnded()
    fun error() = worker.error()
    fun finish() = worker.finish()
    fun pause() = worker.pause()
    fun resume() = worker.resume()
    fun flush() = worker.flush()

    fun stop() {
        worker.stop()
        floatBuffers.clear()
        integerBuffers.clear()
    }
}
