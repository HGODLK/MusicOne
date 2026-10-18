package com.musicone.nativeaudio

import androidx.annotation.Keep

/** USB 不接受源规格时按连续音频流转换，输出小端交错 S32；仅由写线程使用。 */
@Keep
class NativePcmResampler(inputRate: Int, outputRate: Int, channels: Int, encoding: Int) : AutoCloseable {
    private var handle: Long

    init {
        NativeAudioBridge.ensureLoaded()
        handle = nativeCreate(inputRate, outputRate, channels, encoding)
        check(handle != 0L) { "无法创建 PCM 兼容转换器" }
    }

    fun convert(bytes: ByteArray, gain: Double = 1.0): ByteArray = nativeConvert(requireHandle(), bytes, gain)
    fun drain(gain: Double = 1.0): ByteArray = nativeConvert(requireHandle(), null, gain)
    fun reset() { check(nativeReset(requireHandle())) { "PCM 转换器复位失败" } }
    private fun requireHandle(): Long = handle.also { check(it != 0L) { "PCM 转换器已释放" } }

    override fun close() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L }
    }

    private external fun nativeCreate(inputRate: Int, outputRate: Int, channels: Int, encoding: Int): Long
    private external fun nativeConvert(handle: Long, bytes: ByteArray?, gain: Double): ByteArray
    private external fun nativeReset(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)
}
