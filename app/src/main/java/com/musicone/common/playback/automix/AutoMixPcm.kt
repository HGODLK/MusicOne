package com.musicone

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 自然过渡支持的整数 / 浮点 PCM 输入格式（播放链路解码器输出）。 */
internal data class AutoMixPcmFormat(val sampleRate: Int, val channels: Int, val encoding: Int) {
    val bytesPerSample: Int get() = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
    val bytesPerFrame: Int get() = bytesPerSample * channels
    val supported: Boolean get() = bytesPerSample > 0 && channels in 1..2 && sampleRate > 0

    /** 日志用的可读格式，如“44.1kHz/16bit/立体声”。 */
    val label: String get() {
        val rate = if (sampleRate % 1000 == 0) "${sampleRate / 1000}kHz" else "%.1fkHz".format(sampleRate / 1000.0)
        val depth = when (encoding) {
            C.ENCODING_PCM_16BIT -> "16bit"
            C.ENCODING_PCM_24BIT -> "24bit"
            C.ENCODING_PCM_32BIT -> "32bit"
            C.ENCODING_PCM_FLOAT -> "32bit浮点"
            else -> "编码$encoding"
        }
        val ch = when (channels) { 1 -> "单声道"; 2 -> "立体声"; else -> "${channels}声道" }
        return "$rate/$depth/$ch"
    }

    fun framesToUs(frames: Long): Long = frames * C.MICROS_PER_SECOND / sampleRate
    fun usToFrames(us: Long): Long = us * sampleRate / C.MICROS_PER_SECOND
}

/** 从 buffer 当前位置读 frames 帧，按立体声浮点写入 out（单声道复制到两边），不移动 buffer 位置。 */
internal fun readPcmFrames(buffer: ByteBuffer, format: AutoMixPcmFormat, frames: Int, out: FloatArray, outOffsetFrames: Int = 0) {
    val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    var p = b.position()
    for (i in 0 until frames) {
        var l = 0f
        var r = 0f
        for (c in 0 until format.channels) {
            val v = when (format.encoding) {
                C.ENCODING_PCM_16BIT -> b.getShort(p) / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val x = (b.get(p).toInt() and 0xff) or ((b.get(p + 1).toInt() and 0xff) shl 8) or (b.get(p + 2).toInt() shl 16)
                    x / 8388608f
                }
                C.ENCODING_PCM_32BIT -> (b.getInt(p) / 2147483648.0).toFloat()
                else -> b.getFloat(p)
            }
            p += format.bytesPerSample
            if (c == 0) l = v else r = v
        }
        if (format.channels == 1) r = l
        out[2 * (outOffsetFrames + i)] = l
        out[2 * (outOffsetFrames + i) + 1] = r
    }
}

/** 把立体声浮点帧按目标格式编码到 dst（单声道取左右平均），返回写入字节数。 */
internal fun writePcmFrames(src: FloatArray, srcOffsetFrames: Int, frames: Int, format: AutoMixPcmFormat, dst: ByteBuffer) {
    dst.order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until frames) {
        val l = src[2 * (srcOffsetFrames + i)]
        val r = src[2 * (srcOffsetFrames + i) + 1]
        if (format.channels == 1) putSample((l + r) * 0.5f, format, dst)
        else { putSample(l, format, dst); putSample(r, format, dst) }
    }
}

private fun putSample(v: Float, format: AutoMixPcmFormat, dst: ByteBuffer) {
    when (format.encoding) {
        C.ENCODING_PCM_16BIT -> dst.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        C.ENCODING_PCM_24BIT -> {
            val x = (v.coerceIn(-1f, 1f) * 8388607f).toInt()
            dst.put((x and 0xff).toByte()); dst.put(((x shr 8) and 0xff).toByte()); dst.put(((x shr 16) and 0xff).toByte())
        }
        C.ENCODING_PCM_32BIT -> dst.putInt((v.coerceIn(-1f, 1f) * 2147483647.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        else -> dst.putFloat(v)
    }
}

/** 立体声浮点先进先出队列，按绝对帧号访问。 */
internal class AutoMixFrameQueue(initialFrames: Int = 1 shl 16) {
    private var data = FloatArray(initialFrames * 2)
    /** 队首帧的绝对帧号。 */
    var start = 0L
        private set
    var size = 0
        private set
    val end: Long get() = start + size

    fun append(src: FloatArray, frames: Int) {
        if (size + frames > data.size / 2) data = data.copyOf(maxOf(data.size * 2, (size + frames) * 2))
        System.arraycopy(src, 0, data, size * 2, frames * 2)
        size += frames
    }

    fun has(frame: Long) = frame in start until end
    fun left(frame: Long) = data[((frame - start) * 2).toInt()]
    fun right(frame: Long) = data[((frame - start) * 2 + 1).toInt()]

    fun dropBefore(frame: Long) {
        val n = (frame - start).coerceIn(0L, size.toLong()).toInt()
        if (n == 0) return
        System.arraycopy(data, n * 2, data, 0, (size - n) * 2)
        size -= n
        start += n
    }

    fun clear(newStart: Long = 0L) { size = 0; start = newStart }
}
