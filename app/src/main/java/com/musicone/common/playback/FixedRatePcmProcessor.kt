package com.musicone

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.musicone.nativeaudio.NativePcmResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 立体声交错浮点重采样器（固定采样率输出层用）。 */
internal interface FixedRateResampler : AutoCloseable {
    /** 转换 src 的前 frames 帧，返回本次产出（可能为空，转换器内部有延迟）。 */
    fun convert(src: FloatArray, frames: Int): FloatArray
    /** 冲出内部残留。 */
    fun drain(): FloatArray
}

/** 原生转换器按浮点输入创建，输出小端交错 S32。 */
internal fun nativeFixedRateResampler(inputRate: Int, outputRate: Int): FixedRateResampler =
    NativeFixedRateResampler(inputRate, outputRate)

private class NativeFixedRateResampler(inputRate: Int, outputRate: Int) : FixedRateResampler {
    private val native = NativePcmResampler(inputRate, outputRate, 2, C.ENCODING_PCM_FLOAT)

    override fun convert(src: FloatArray, frames: Int): FloatArray {
        if (frames <= 0) return EMPTY
        val bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * 2) bytes.putFloat(src[i])
        return s32ToFloat(native.convert(bytes.array()))
    }

    override fun drain(): FloatArray = s32ToFloat(native.drain())

    override fun close() = native.close()

    private fun s32ToFloat(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getInt(it * 4) / 2147483648f }
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}

/**
 * 把 16/24/32 位整数或浮点、1～2 声道的 PCM 统一成 targetRate 的立体声浮点（单声道复制到两边）。
 * 输入已是目标格式时不工作，由处理链直通。
 */
@OptIn(UnstableApi::class)
internal class FixedRatePcmProcessor(
    private val targetRate: Int,
    private val resamplerFactory: (inputRate: Int, outputRate: Int) -> FixedRateResampler = ::nativeFixedRateResampler,
) : BaseAudioProcessor() {
    private var resampler: FixedRateResampler? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in SUPPORTED_ENCODINGS || inputAudioFormat.channelCount !in 1..2 ||
            inputAudioFormat.sampleRate <= 0
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        if (inputAudioFormat.sampleRate == targetRate && inputAudioFormat.channelCount == 2 &&
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        return AudioProcessor.AudioFormat(targetRate, 2, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive) return
        val format = inputAudioFormat
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames <= 0) return
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        readPcmFrames(inputBuffer, AutoMixPcmFormat(format.sampleRate, format.channelCount, format.encoding), frames, scratch)
        inputBuffer.position(inputBuffer.position() + frames * format.bytesPerFrame)
        val r = resampler
        if (r == null) {
            write(scratch, frames)
        } else {
            val out = r.convert(scratch, frames)
            write(out, out.size / 2)
        }
    }

    override fun onQueueEndOfStream() {
        val tail = resampler?.drain() ?: return
        write(tail, tail.size / 2)
    }

    override fun onFlush() {
        resampler?.close()
        resampler = null
        // configure 抛异常时基类仍记下了输入格式，以是否工作为准，不按被拒绝的格式建转换器
        if (isActive && inputAudioFormat.sampleRate != targetRate) {
            resampler = resamplerFactory(inputAudioFormat.sampleRate, targetRate)
        }
    }

    override fun onReset() {
        resampler?.close()
        resampler = null
        scratch = FloatArray(0)
    }

    /** 中转缓冲可能比本次大，只写出前 frames 帧。 */
    private fun write(src: FloatArray, frames: Int) {
        if (frames <= 0) return
        val out = replaceOutputBuffer(frames * 8)
        for (i in 0 until frames * 2) out.putFloat(src[i])
        out.flip()
    }

    private companion object {
        val SUPPORTED_ENCODINGS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }
}
