package com.musicone.demo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * 自动选择 Android 14 系统 Bit-Perfect 或 UAC2 独占输出，失败时保留普通 Media3 输出。
 */
@OptIn(UnstableApi::class)
internal class MusicOneUsbAudioSink(
    private val delegate: DefaultAudioSink,
    context: Context,
    enabled: Boolean,
) : ForwardingAudioSink(delegate) {
    private val applicationContext = context.applicationContext
    private val usbDevice = UsbAudioDevice.getInstance(applicationContext)
    private val systemBitPerfect = AndroidBitPerfectOutput(applicationContext)
    private val systemMediaVolume = SystemMediaVolumeGain(applicationContext)
    private val playbackWakeGuard = UsbPlaybackWakeGuard(applicationContext)
    @Volatile private var enabled = enabled
    @Volatile private var routeChangePending = false
    private val routeChangeLock = Any()
    private var routeChangeCompletion: CompletableDeferred<Unit>? = null
    private var inputFormat: Format? = null
    private var stream: UsbAudioStream? = null
    private var streamingThread: DirectUsbStreamingThread? = null
    private var directInterfaceId: Int? = null
    private var directSampleRate = 0
    private var currentSampleRate = 0
    private var currentChannelCount = 0
    private var currentEncoding = C.ENCODING_PCM_16BIT
    private var currentSourceBitDepth = 16
    @Volatile private var pendingSourceBitDepth = 16
    @Volatile private var dolbySource = false
    private var directStartMediaTimeUs = 0L
    private var directPositionNeedsStart = true
    private var delegateMuted = false
    @Volatile private var playbackStarted = false
    @Volatile private var pendingVolume = 1f

    fun setEnabled(value: Boolean): Deferred<Unit>? {
        if (enabled == value) return null
        enabled = value
        updateDirectPlaybackWakeLock()
        val completion = requestRouteChange()
        if (!value) {
            UsbAudioOutputStatus.publish(UsbAudioOutputSnapshot(UsbAudioOutputRoute.DISABLED))
        }
        return completion
    }

    fun onDeviceChanged(): Deferred<Unit> = requestRouteChange()

    fun setSourceFormat(format: Format?) {
        dolbySource = isDolbyAudioMime(format?.sampleMimeType)
        val bitDepth = sourceAudioBitDepth(format)
        pendingSourceBitDepth = bitDepth.takeIf { it in SUPPORTED_BIT_DEPTHS } ?: 16
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val changingRoute = routeChangePending
        val nextEncoding = inputFormat.pcmEncoding.takeIf { it != Format.NO_VALUE } ?: C.ENCODING_PCM_16BIT
        val nextSourceBitDepth = usbPcmSourceBitDepth(inputFormat, pendingSourceBitDepth)
        val reuseDirectOutput = !changingRoute && enabled && !dolbySource && stream?.isAlive == true && streamingThread != null &&
            currentSampleRate == inputFormat.sampleRate && currentChannelCount == inputFormat.channelCount &&
            currentEncoding == nextEncoding && currentSourceBitDepth == nextSourceBitDepth
        this.inputFormat = inputFormat
        currentSampleRate = inputFormat.sampleRate
        currentChannelCount = inputFormat.channelCount
        currentEncoding = nextEncoding
        currentSourceBitDepth = nextSourceBitDepth
        directPositionNeedsStart = true
        try {
            if (reuseDirectOutput) flushDirectOutput() else selectOutput(inputFormat)
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            if (stream?.isAlive == true) muteDelegate() else unmuteDelegate()
        } finally {
            if (changingRoute) finishRouteChange()
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (routeChangePending) return false
        val activeStream = stream
        val worker = streamingThread
        // 设置切换与解码器复用可能先于输出重建，旧独占流也不能接收杜比降混数据。
        if (dolbySource && activeStream != null) return false
        if (activeStream != null && worker != null) {
            throwDirectWriteError()
            if (directPositionNeedsStart) {
                directStartMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
                directPositionNeedsStart = false
            }
            val snapshot = buffer.slice().order(buffer.order())
            val accepted = if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                worker.enqueueFloat(snapshot)
            } else {
                worker.enqueueInteger(snapshot, currentEncoding, currentSourceBitDepth)
            }
            if (!accepted) return false
            buffer.position(buffer.limit())
            return true
        }
        unmuteDelegate()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val activeStream = stream
        if (activeStream != null) {
            if (directPositionNeedsStart || directSampleRate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
            return directStartMediaTimeUs + (streamingThread?.completedFrames ?: 0L) * C.MICROS_PER_SECOND / directSampleRate
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    override fun hasPendingData(): Boolean =
        if (stream != null) streamingThread?.hasPendingData() == true else super.hasPendingData()

    override fun isEnded(): Boolean =
        if (stream != null) streamingThread?.isEnded() == true else super.isEnded()

    override fun playToEndOfStream() {
        if (stream != null) {
            throwDirectWriteError()
            streamingThread?.finish()
        } else super.playToEndOfStream()
    }

    private fun throwDirectWriteError() {
        streamingThread?.error()?.let { cause ->
            throw AudioSink.WriteException(-1, requireNotNull(inputFormat), false).apply { initCause(UsbOutputException(cause)) }
        }
    }

    override fun play() {
        super.play()
        playbackStarted = true
        streamingThread?.resume()
        updateDirectPlaybackWakeLock()
    }

    override fun pause() {
        playbackStarted = false
        streamingThread?.pause()
        updateDirectPlaybackWakeLock()
        super.pause()
    }

    override fun flush() {
        if (routeChangePending) {
            try {
                resetOutputRoute()
                super.reset()
            } finally {
                finishRouteChange()
            }
            return
        }
        flushDirectOutput()
        super.flush()
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (stream?.isAlive == true) muteDelegate() else super.setVolume(volume)
    }

    override fun reset() {
        if (routeChangePending) {
            try {
                resetOutputRoute()
            } finally {
                finishRouteChange()
            }
        } else {
            flushDirectOutput()
        }
        super.reset()
    }

    override fun release() {
        playbackStarted = false
        releaseDirectOutput()
        playbackWakeGuard.release()
        systemBitPerfect.clear()
        finishRouteChange()
        super.release()
    }

    private fun requestRouteChange(): Deferred<Unit> = synchronized(routeChangeLock) {
        routeChangePending = true
        routeChangeCompletion?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>().also {
            routeChangeCompletion = it
        }
    }

    private fun finishRouteChange() {
        val completion = synchronized(routeChangeLock) {
            routeChangePending = false
            routeChangeCompletion.also { routeChangeCompletion = null }
        }
        completion?.complete(Unit)
    }

    private fun selectOutput(format: Format) {
        systemBitPerfect.clear()
        releaseDirectOutput()
        if (!enabled) {
            UsbAudioOutputStatus.publish(UsbAudioOutputSnapshot(UsbAudioOutputRoute.DISABLED))
            return
        }
        if (dolbySource || !isSupportedPcm(format)) {
            publishFallback("当前解码格式无法直通，继续使用安卓音频输出")
            return
        }

        val systemSession = systemBitPerfect.prepare(format, currentSourceBitDepth)
        if (systemSession != null) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(
                    UsbAudioOutputRoute.SYSTEM_BIT_PERFECT,
                    systemSession.deviceName,
                    systemSession.sampleRate,
                    systemSession.bitDepth,
                    "系统 Bit-Perfect 通道",
                ),
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(
                    UsbAudioOutputRoute.UNSUPPORTED_ANDROID,
                    detail = "USB 独占输出需要 Android 10 或更高版本",
                ),
            )
            return
        }
        configureDirectOutput(format)
    }

    private fun configureDirectOutput(format: Format) {
        val device = usbDevice.findUsbAudioDevice()
        if (device == null) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(
                    UsbAudioOutputRoute.WAITING_FOR_DEVICE,
                    detail = "等待连接 USB Audio Class 2.0 音频设备",
                ),
            )
            return
        }
        val deviceName = device.productName ?: "USB 音频设备"
        if (!usbDevice.hasPermission(device)) {
            UsbAudioOutputStatus.publish(
                UsbAudioOutputSnapshot(
                    UsbAudioOutputRoute.PERMISSION_REQUIRED,
                    deviceName,
                    detail = "需要允许 MusicOne 访问该 USB 设备",
                ),
            )
            Handler(Looper.getMainLooper()).post {
                UsbAudioPermissionCoordinator.requestConnectedDevice(applicationContext)
            }
            return
        }
        val deviceInfo = usbDevice.openDevice(device)
        if (deviceInfo == null) {
            publishFallback("无法接管 USB 音频接口")
            return
        }
        val output = createDirectUsbPcmOutput(usbDevice, deviceInfo, format, currentSourceBitDepth)
        if (output == null) {
            usbDevice.closeDevice()
            publishFallback("USB 不接受源规格或 32bit／192kHz 兼容规格，继续使用系统输出")
            return
        }
        val candidate = output.stream
        val streamProfile = output.profile
        stream = candidate
        directInterfaceId = streamProfile.interfaceId
        directSampleRate = output.sampleRate
        UsbExclusiveVolumeKeys.activeGain = systemMediaVolume
        streamingThread = DirectUsbStreamingThread(candidate, format.sampleRate, format.channelCount,
            output.sampleRate, output.resampler) {
            (pendingVolume * systemMediaVolume.current()).coerceIn(0f, 1f)
        }.also {
            if (!playbackStarted) it.pause()
            it.start()
        }
        updateDirectPlaybackWakeLock()
        forceDelegateToSpeaker()
        muteDelegate()
        UsbAudioOutputStatus.publish(
            UsbAudioOutputSnapshot(
                UsbAudioOutputRoute.DIRECT_UAC2,
                deviceInfo.deviceName,
                output.sampleRate,
                streamProfile.bitDepth,
                if (output.converted) "已转换为兼容的 32bit／192kHz PCM，并跟随系统媒体音量"
                else "已绕过安卓混音与 SRC，并通过软件增益跟随系统媒体音量",
                converted = output.converted,
            ),
        )
    }

    private fun releaseDirectOutput() {
        if (UsbExclusiveVolumeKeys.activeGain === systemMediaVolume) UsbExclusiveVolumeKeys.activeGain = null
        val activeStream = stream
        stream = null
        directSampleRate = 0
        updateDirectPlaybackWakeLock()
        try {
            if (activeStream != null) {
                activeStream.stop()
                streamingThread?.stop()
                streamingThread = null
                releaseUsbStream(activeStream::release, {
                    runCatching { usbDevice.setAltSetting(0, directInterfaceId) }
                    usbDevice.closeDevice()
                }, activeStream::releaseAfterDeviceClosed)
                directInterfaceId = null
            } else {
                streamingThread?.stop()
                streamingThread = null
                directInterfaceId = null
            }
        } finally {
            clearDelegateRoute()
            unmuteDelegate()
        }
    }

    private fun flushDirectOutput() {
        try {
            streamingThread?.flush()
        } catch (failure: IllegalStateException) {
            throw UsbOutputException(failure)
        }
        directPositionNeedsStart = true
    }

    private fun resetOutputRoute() {
        releaseDirectOutput()
        systemBitPerfect.clear()
        inputFormat = null
        directPositionNeedsStart = true
    }

    private fun forceDelegateToSpeaker() {
        val manager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker != null) runCatching { delegate.setPreferredDevice(speaker) }
    }

    private fun clearDelegateRoute() {
        runCatching { delegate.setPreferredDevice(null) }
    }

    private fun muteDelegate() {
        if (!delegateMuted) {
            super.setVolume(0f)
            delegateMuted = true
        }
    }

    private fun unmuteDelegate() {
        if (delegateMuted) {
            super.setVolume(pendingVolume)
            delegateMuted = false
        }
    }

    private fun publishFallback(detail: String) {
        Log.w(TAG, detail)
        UsbAudioOutputStatus.publish(UsbAudioOutputSnapshot(UsbAudioOutputRoute.FALLBACK, detail = detail))
    }

    private fun isSupportedPcm(format: Format): Boolean =
        format.sampleRate > 0 && format.channelCount in 1..2 && format.pcmEncoding in SUPPORTED_PCM_ENCODINGS

    private fun Int.bitDepth(): Int = when (this) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    private companion object {
        const val TAG = "MusicOneUsbAudio"
        val SUPPORTED_PCM_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
        val SUPPORTED_BIT_DEPTHS = setOf(16, 24, 32)
    }

    private fun updateDirectPlaybackWakeLock() {
        playbackWakeGuard.setActive(enabled && playbackStarted && stream?.isAlive == true)
    }
}
