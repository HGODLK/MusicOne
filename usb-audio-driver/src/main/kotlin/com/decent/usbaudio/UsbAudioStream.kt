package com.decent.usbaudio

import android.util.Log

/**
 * JNI handle for a direct USB audio output stream.
 *
 * This class is the Kotlin counterpart of the native [UsbAudioContext] defined
 * in [usb-audio-output.cpp]. It sends PCM data directly to a USB Audio Class 2.0
 * DAC via Linux usbdevfs isochronous transfers, bypassing the entire Android
 * audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * Pipeline: 80 URBs in flight, each carrying 8 ISO packets (1 ms of audio).
 * Total pipeline buffer: ~80 ms.
 *
 * Rate transition lifecycle (per USB Audio Class 2.0 spec, verified via xHCI ftrace):
 * ```
 * stream.stop()           // stop accepting writes
 * stream.drainUrbs()      // 取消并回收所有在途请求
 * stream.release()        // free native context
 * // Kotlin layer does: setAlt(0) -> SET_CUR rate -> setAlt(N)
 * newStream = UsbAudioStream(fd, ...)
 * newStream.start()
 * newStream.write(pcm)    // pipeline fills naturally
 * ```
 *
 * @param fd               File descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
 * @param interfaceId      Audio streaming interface number (typically 1)
 * @param endpointOut      Isochronous OUT endpoint address (e.g. 0x01)
 * @param endpointFeedback Feedback IN endpoint address (e.g. 0x81), or 0 if unused
 * @param sampleRate       Initial sample rate in Hz
 * @param channelCount     Number of channels (1=mono, 2=stereo)
 * @param bitDepth         Bits per sample (16, 24, or 32)
 * @param subslotSizeBytes USB 子槽大小；24 位设备可能使用 3 或 4 字节子槽
 * @param maxPacketSize    Max packet size from endpoint descriptor
 */
class UsbAudioStream(
        fd: Int,
        interfaceId: Int,
        endpointOut: Int,
        endpointFeedback: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        subslotSizeBytes: Int,
        maxPacketSize: Int
) {

    /** Native UsbAudioContext pointer. Exposed for NativeAudioEngine which
     *  shares the same USB context for direct submitPcmToUrbs calls. */
    var nativeHandle: Long = 0L
        private set

    init {
        nativeHandle = nativeUsbAudioCreate(
                fd, interfaceId, endpointOut, endpointFeedback,
                sampleRate, channelCount, bitDepth, subslotSizeBytes, maxPacketSize
        )
        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeUsbAudioCreate returned 0 — check logcat for native errors")
        }
    }

    /** True when the native context was created successfully. */
    val isReady: Boolean
        get() = nativeHandle != 0L

    /** True when the stream is actively running (after start, before stop/error). */
    val isAlive: Boolean
        get() = nativeHandle != 0L && nativeIsRunning(nativeHandle)

    /** 本次位置基准以来成功完成 USB 传输的音频帧数。 */
    val completedFrames: Long
        get() = if (nativeHandle != 0L) nativeGetCompletedFrames(nativeHandle) else 0L

    /**
     * Select alternate setting on the USB streaming interface.
     * This determines the active format (bit depth) of the endpoint.
     *
     * @param altSetting Alternate setting number (1-4 typically)
     * @return True on success
     */
    fun setAltSetting(altSetting: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetAltSetting(nativeHandle, altSetting)
    }

    /**
     * Configure the DAC's sample rate via UAC2 SET_CUR control request.
     *
     * @param sampleRateHz  Target sample rate (e.g. 44100, 96000)
     * @param clockSourceId UAC2 Clock Source entity ID. Pass 0 to skip
     *                      (some DACs auto-detect from the data stream).
     * @return True on success
     */
    fun setSampleRate(sampleRateHz: Int, clockSourceId: Int = 0): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetSampleRate(nativeHandle, sampleRateHz, clockSourceId)
    }

    /**
     * Start the USB audio stream. The pipeline fills naturally with
     * data from subsequent [write] calls.
     */
    fun start(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioStart(nativeHandle)
    }

    /**
     * Write interleaved float32 PCM to the USB DAC.
     *
     * The native layer converts to the target bit depth, splits into
     * 1ms URBs (8 ISO packets each), and manages the 64-URB pipeline.
     * This call blocks when the pipeline is full (natural backpressure
     * matching the DAC's clock rate).
     *
     * @param pcmBuffer Interleaved float PCM; length = frameCount * channelCount
     */
    fun write(pcmBuffer: FloatArray) {
        if (nativeHandle == 0L) return
        nativeUsbAudioWrite(nativeHandle, pcmBuffer)
    }

    /**
     * Write raw integer PCM bytes directly to the USB DAC (no float conversion).
     *
     * The native layer pads the input bit depth to the DAC's bit depth using
     * lossless integer operations (shift left, zero-fill LSBs). This is the
     * true bit-perfect path — zero float math in the entire pipeline.
     *
     * @param pcmBuffer Raw PCM bytes (interleaved, little-endian)
     * @param encoding  Media3 PCM 容器格式
     * @param validBitDepth 源音频的有效位深
     */
    fun writeRaw(pcmBuffer: ByteArray, encoding: Int, validBitDepth: Int) {
        if (nativeHandle == 0L) return
        val inputBitDepth = when (encoding) {
            2 -> 16   // C.ENCODING_PCM_16BIT
            0x15 -> 24 // C.ENCODING_PCM_24BIT
            0x16 -> 32 // C.ENCODING_PCM_32BIT
            else -> return
        }
        nativeUsbAudioWriteRaw(nativeHandle, pcmBuffer, inputBitDepth, validBitDepth)
    }

    /**
     * 发送原子中断信号；必须等写线程退出后才可清理或释放。
     */
    fun stop() {
        if (nativeHandle == 0L) return
        nativeUsbAudioStop(nativeHandle)
    }

    /** 写线程退出旧写入后取消并回收传输，再恢复同规格会话。 */
    fun flush() {
        check(nativeHandle != 0L && nativeFlush(nativeHandle)) { "USB 清理失败" }
    }

    /** 仅由写线程调用，回收已完成请求并检查残余 PCM 和在途音频。 */
    fun pollPending(): Boolean {
        check(nativeHandle != 0L) { "USB 会话已释放" }
        val pending = nativePollPending(nativeHandle)
        check(pending >= 0) { "USB 输出中断或传输失败" }
        return pending != 0
    }

    /** 提交不足常规批次的尾音，并等待音频请求完成。 */
    fun finish() {
        check(nativeHandle != 0L && nativeFinish(nativeHandle)) { "USB 曲末传输失败或已中断" }
    }

    /** 停止写线程后取消并回收请求；不能将取消视为正常播放完成。 */
    fun drainUrbs(): Int {
        if (nativeHandle == 0L) return 0
        return nativeDrainUrbs(nativeHandle).also { check(it >= 0) { "USB 请求回收失败" } }
    }

    /**
     * Release all native resources. The instance must not be used after this.
     */
    fun release() {
        if (nativeHandle == 0L) return
        check(nativeUsbAudioDestroy(nativeHandle)) { "USB 请求未回收，保留原生资源" }
        nativeHandle = 0L
        Log.i(TAG, "UsbAudioStream released")
    }

    // JNI declarations

    private external fun nativeUsbAudioCreate(
            fd: Int, interfaceId: Int, endpointOut: Int, endpointFeedback: Int,
            sampleRate: Int, channelCount: Int, bitDepth: Int, subslotSizeBytes: Int,
            maxPacketSize: Int
    ): Long

    private external fun nativeUsbAudioSetAltSetting(handle: Long, altSetting: Int): Boolean
    private external fun nativeUsbAudioSetSampleRate(handle: Long, sampleRateHz: Int, clockSourceId: Int): Boolean
    private external fun nativeUsbAudioStart(handle: Long): Boolean
    private external fun nativeUsbAudioWrite(handle: Long, pcmBuffer: FloatArray)
    private external fun nativeUsbAudioWriteRaw(
        handle: Long,
        pcmBuffer: ByteArray,
        inputBitDepth: Int,
        validBitDepth: Int,
    )
    private external fun nativeUsbAudioStop(handle: Long)
    private external fun nativeFlush(handle: Long): Boolean
    private external fun nativePollPending(handle: Long): Int
    private external fun nativeFinish(handle: Long): Boolean
    private external fun nativeDrainUrbs(handle: Long): Int
    private external fun nativeUsbAudioDestroy(handle: Long): Boolean
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetCompletedFrames(handle: Long): Long

    companion object {
        private const val TAG = "UsbAudioStream"

        init {
            System.loadLibrary("decent_usb_audio")
        }

        /**
         * Perform a USB port reset on the device. Resets the DAC's clock state.
         * The fd remains valid but all interface claims are released.
         * @return 0 on success, negative on error
         */
        @JvmStatic
        external fun nativeUsbReset(fd: Int): Int
    }
}
