package com.musicone.nativeaudio

import android.media.AudioRouting
import androidx.annotation.Keep
import java.nio.ByteBuffer

/** 原厂 LG 高位整数 PCM 入口；最终是否进入 Direct 由厂商音频策略决定。 */
@Keep
class NativeLgPcmOutput(sampleRate: Int, channels: Int, bitDepth: Int) : AutoCloseable {
    private var handle: Long
    private var reportedEmptyEvents = 0L
    val routing: AudioRouting

    init {
        NativeAudioBridge.ensureLoaded()
        handle = nativeOpen(sampleRate, channels, bitDepth)
        check(handle != 0L) { "LG 驱动未接受 ${bitDepth}bit / ${sampleRate}Hz PCM" }
        try {
            routing = checkNotNull(nativeRouting(handle)) { "LG 输出未提供路由控制" }
        } catch (failure: Throwable) {
            nativeClose(handle)
            handle = 0
            throw failure
        }
    }

    fun enqueue(buffer: ByteBuffer): Int {
        val accepted = nativeEnqueue(handle, buffer, buffer.remaining())
        val emptyEvents = nativeEmptyEvents(handle)
        if (emptyEvents != reportedEmptyEvents) {
            android.util.Log.w("MusicOneLgAudio", "LG 输出队列耗尽，累计次数=$emptyEvents")
            reportedEmptyEvents = emptyEvents
        }
        return accepted
    }
    fun drain() { check(nativeDrain(handle)) { "LG 输出尾部提交失败" } }
    fun play(playing: Boolean) { check(nativePlay(handle, playing)) { "LG 输出播放状态切换失败" } }
    fun volume(gain: Float) { check(nativeVolume(handle, gain)) { "LG 输出音量设置失败" } }
    fun completedFrames(): Long = nativeCompletedFrames(handle)
    fun hasPendingData(): Boolean = nativeHasPendingData(handle)
    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0L } }

    private external fun nativeOpen(rate: Int, channels: Int, depth: Int): Long
    private external fun nativeRouting(handle: Long): AudioRouting?
    private external fun nativeEnqueue(handle: Long, buffer: ByteBuffer, bytes: Int): Int
    private external fun nativeDrain(handle: Long): Boolean
    private external fun nativeEmptyEvents(handle: Long): Long
    private external fun nativePlay(handle: Long, playing: Boolean): Boolean
    private external fun nativeVolume(handle: Long, gain: Float): Boolean
    private external fun nativeCompletedFrames(handle: Long): Long
    private external fun nativeHasPendingData(handle: Long): Boolean
    private external fun nativeClose(handle: Long)
}
