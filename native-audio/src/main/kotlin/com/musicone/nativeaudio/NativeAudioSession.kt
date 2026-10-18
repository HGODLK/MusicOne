package com.musicone.nativeaudio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 封装 Native 会话与生命周期管理，提供批量 PCM 读取与 Seek。
 */
@OptIn(UnstableApi::class)
class NativeAudioSession(
    private val dataSource: DataSource,
    private val dataSpec: DataSpec,
    private val targetDsdSampleRate: Int = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
    private val context: Context? = null,
) : AutoCloseable {

    private var handle: Long = 0L
    private var ioBridge: NativeAudioIoBridge? = null
    private var _format: NativeAudioSourceInfo? = null
    val format: NativeAudioSourceInfo get() = requireNotNull(_format) { "会话尚未打开" }

    private var directBuffer: ByteBuffer? = null
    private var isClosed = false
    private val lock = Any()
    private val handleLock = Any()

    fun open(): NativeAudioSourceInfo = synchronized(lock) {
        check(!isClosed) { "Session already closed" }
        NativeAudioBridge.ensureLoaded()

        val bridge = NativeAudioIoBridge(dataSource, dataSpec, context)
        ioBridge = bridge

        val nativeHandle = NativeAudioBridge.nativeCreateSession(bridge, targetDsdSampleRate)
        if (nativeHandle == 0L) {
            bridge.close()
            error("创建原生音频会话失败")
        }
        synchronized(handleLock) { handle = nativeHandle }

        val raw = NativeAudioBridge.nativeOpenSession(nativeHandle)
        if (raw == null || !raw.success) {
            val err = raw?.errorMessage ?: "打开原生音频流失败"
            close()
            error(err)
        }

        val info = NativeAudioSourceInfo(
            formatName = raw.formatName,
            sourceBitDepth = raw.sourceBitDepth,
            sourceSampleRate = raw.sourceSampleRate,
            sourceChannels = raw.outputChannels,
            isDsd = raw.isDsd,
            outputSampleRate = raw.outputSampleRate,
            outputChannels = raw.outputChannels,
            outputEncoding = raw.outputEncoding,
            outputBytesPerFrame = raw.outputBytesPerFrame,
            durationUs = raw.durationUs,
        )
        _format = info

        // 分配 20~40ms 的可复用 DirectByteBuffer 缓冲
        // 192000Hz * 8bytes * 40ms / 1000 = 61440 bytes。
        val bufferCapacity = maxOf(64 * 1024, info.outputBytesPerFrame * (info.outputSampleRate / 25))
        directBuffer = ByteBuffer.allocateDirect(bufferCapacity).order(ByteOrder.LITTLE_ENDIAN)

        return info
    }

    /**
     * 读取 PCM 数据块。
     * @param targetBuffer 接收 PCM 数据的 DirectByteBuffer
     * @param ptsOut 输出该批次起始时间戳（微秒）的单元素数组
     * @return 实际读取的字节数，或者 -1 表示流结束（EOF）
     */
    fun readPcm(targetBuffer: ByteBuffer, ptsOut: LongArray): Int = synchronized(lock) {
        if (isClosed || handle == 0L) return -1
        val maxBytes = targetBuffer.remaining()
        if (maxBytes <= 0) return 0

        val bytesRead = NativeAudioBridge.nativeReadPcm(handle, targetBuffer, maxBytes, ptsOut)
        if (bytesRead > 0) {
            targetBuffer.position(targetBuffer.position() + bytesRead)
        }
        return bytesRead
    }

    /**
     * 使用内部可复用的 DirectBuffer 读取 PCM 数据。
     * 返回读取到的 ByteBuffer（position=0, limit=bytesRead），或 null 表示 EOF。
     */
    fun readNextPcmChunk(ptsOut: LongArray, maxBytes: Int = Int.MAX_VALUE): ByteBuffer? = synchronized(lock) {
        if (isClosed || handle == 0L) return null
        val buf = requireNotNull(directBuffer)
        buf.clear()
        val read = NativeAudioBridge.nativeReadPcm(handle, buf, minOf(buf.capacity(), maxBytes), ptsOut)
        if (read < 0) return null
        buf.position(0)
        buf.limit(read)
        return buf
    }

    fun seekTo(timeUs: Long): Boolean = synchronized(lock) {
        if (isClosed || handle == 0L) return false
        return NativeAudioBridge.nativeSeekTo(handle, timeUs)
    }

    fun cancel(): Unit {
        synchronized(handleLock) {
            if (handle != 0L) {
                NativeAudioBridge.nativeCancelSession(handle)
            }
            // 暂停旧解码任务时保留输入句柄，由下一次 seek 复位取消状态。
        }
    }

    override fun close() = synchronized(lock) {
        if (!isClosed) {
            isClosed = true
            synchronized(handleLock) {
                if (handle != 0L) {
                    NativeAudioBridge.nativeCloseSession(handle)
                    handle = 0L
                }
            }
            ioBridge?.close()
            ioBridge = null
            directBuffer = null
        }
    }
}
