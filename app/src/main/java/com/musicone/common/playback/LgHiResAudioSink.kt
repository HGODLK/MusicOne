package com.musicone

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.musicone.nativeaudio.NativeLgPcmOutput
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** 只接管原厂 G7／G8 有线耳机的整数 PCM，其他输出继续交给原有 Sink。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LgHiResAudioSink(context: Context, delegate: AudioSink) : ForwardingAudioSink(delegate) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wake = UsbPlaybackWakeGuard(context)
    private var output: NativeLgPcmOutput? = null
    private var input: Format? = null
    private var pending: Format? = null
    private var bufferSize = 0
    private var channels: IntArray? = null
    private var depth = 24
    private var sourceDepth = 16
    private var dolby = false
    private var volume = 1f
    private var playing = false
    private var ended = false
    private var playbackClock: LgPcmPlaybackClock? = null
    private var packed = ByteBuffer.allocateDirect(1)
    private var parameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var retryAfterFlush = false
    @Volatile private var activeDevice = 0
    @Volatile private var reset: CompletableDeferred<Unit>? = null

    fun setSourceFormat(format: Format?) {
        sourceDepth = sourceAudioBitDepth(format)
        dolby = isDolbyAudioMime(format?.sampleMimeType)
    }

    private fun targetDevice(): AudioDeviceInfo? {
        if (!LgHiResPreferences.enabled.value || !isLgG7G8(Build.MODEL)) return null
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.any(::isUsbAudioOutput)) return null
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
    }

    fun needsRouteRefresh(): Boolean = activeDevice != (targetDevice()?.id ?: 0)
    fun hasLgOutput(): Boolean = activeDevice != 0
    @Synchronized fun requestRouteChange(): Deferred<Unit> =
        reset?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>().also { reset = it }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == "audio/raw" && targetDevice() != null && lgPcmOutputDepth(format, sourceDepth) != null)
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        bufferSize = specifiedBufferSize
        channels = outputChannels
        if (reset == null && output != null) {
            drainOutput()
            if (hasPendingData()) { pending = inputFormat; return }
        }
        try { configureOutput(inputFormat) } finally { finishReset() }
    }

    private fun configureOutput(format: Format) {
        closeOutput()
        input = format
        pending = null
        ended = false
        retryAfterFlush = false
        val device = targetDevice()
        val bits = lgPcmOutputDepth(format, sourceDepth)
        if (device != null && bits != null && !dolby && parameters == PlaybackParameters.DEFAULT && !skipSilence &&
            channels == null && format.encoderDelay == 0 && format.encoderPadding == 0) {
            try {
                super.reset()
                val stream = NativeLgPcmOutput(format.sampleRate, format.channelCount, bits)
                output = stream
                check(stream.routing.setPreferredDevice(device)) { "LG 未接受有线耳机路由" }
                stream.volume(volume)
                stream.play(playing)
                playbackClock = LgPcmPlaybackClock(format.sampleRate)
                depth = bits
                activeDevice = device.id
                // 每块最多约 20ms，原生端合并短块并复用约 200ms 队列；满后立即回压。
                packed = ByteBuffer.allocateDirect((format.sampleRate / 50).coerceAtLeast(1) * format.channelCount * bits / 8)
                PlaybackOutputStatus.clearSystem()
                PlaybackOutputStatus.lgOutput(PlaybackOutputSnapshot("Hi-Res", lgAudioDeviceName(Build.MODEL),
                    format.sampleRate, bits, format.channelCount, note = "应用提交规格；Direct／DAC 最终规格待设备验证"))
                PlaybackOutputStatus.lgDetail("Hi-Res")
                wake.setActive(playing)
                return
            } catch (failure: Exception) {
                closeOutput()
                PlaybackOutputStatus.lgDetail("系统输出")
                android.util.Log.w("MusicOneLgAudio", "LG 高精度输出不可用，回退原路径", failure)
            } catch (failure: LinkageError) {
                closeOutput()
                PlaybackOutputStatus.lgDetail("系统输出")
            }
        } else {
            PlaybackOutputStatus.lgDetail(when {
                !LgHiResPreferences.enabled.value -> "已关闭"
                !isLgG7G8(Build.MODEL) -> "系统输出"
                device == null -> "等待 3.5mm 有线耳机"
                else -> "系统输出"
            })
        }
        super.configure(format, bufferSize, channels)
        super.setPlaybackParameters(parameters)
        super.setSkipSilenceEnabled(skipSilence)
        super.setVolume(volume)
        if (playing) super.play()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (reset != null) return false
        pending?.let {
            drainOutput()
            if (hasPendingData()) return false
            configureOutput(it)
        }
        if (retryAfterFlush) input?.let(::configureOutput)
        val stream = output ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!buffer.hasRemaining()) return true
        val format = requireNotNull(input)
        val result = fillLgPcmQueue(buffer, format.pcmEncoding, depth, format.channelCount, packed,
            enqueue = stream::enqueue, submitted = { frames ->
                requireNotNull(playbackClock).submitted(presentationTimeUs, frames)
                ended = false
            })
        if (result < 0) {
            // 尚未接收的剩余 PCM 交给原路径，已接收部分不会重复提交。
            closeOutput()
            PlaybackOutputStatus.lgDetail("系统输出")
            super.configure(format, bufferSize, channels)
            super.setVolume(volume)
            if (playing) super.play()
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        return result > 0
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val stream = output ?: return super.getCurrentPositionUs(sourceEnded)
        return requireNotNull(playbackClock).positionUs(stream.completedFrames())
    }
    override fun hasPendingData(): Boolean = output?.hasPendingData() ?: super.hasPendingData()
    override fun isEnded(): Boolean = if (output != null) ended && !hasPendingData() else super.isEnded()
    override fun playToEndOfStream() {
        if (output != null) { drainOutput(); ended = true } else super.playToEndOfStream()
    }
    private fun drainOutput() {
        try { output?.drain() } catch (failure: Exception) {
            throw AudioSink.WriteException(-1, requireNotNull(input), false).apply { initCause(failure) }
        }
    }
    override fun play() { playing = true; output?.play(true); wake.setActive(output != null); super.play() }
    override fun pause() { playing = false; output?.play(false); wake.release(); super.pause() }
    override fun setVolume(volume: Float) { this.volume = volume; output?.volume(volume); super.setVolume(volume) }
    override fun handleDiscontinuity() { if (output != null) playbackClock?.discontinuity() else super.handleDiscontinuity() }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        parameters = playbackParameters
        if (output != null && playbackParameters != PlaybackParameters.DEFAULT) pending = input
        super.setPlaybackParameters(playbackParameters)
    }
    override fun getPlaybackParameters(): PlaybackParameters = if (output != null) PlaybackParameters.DEFAULT else super.getPlaybackParameters()
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        if (output != null && skipSilenceEnabled) pending = input
        super.setSkipSilenceEnabled(skipSilenceEnabled)
    }
    override fun flush() {
        closeOutput()
        pending = null
        retryAfterFlush = true
        super.flush()
        finishReset()
    }
    override fun reset() {
        playing = false
        closeOutput()
        input = null
        pending = null
        retryAfterFlush = false
        super.reset()
        finishReset()
    }
    override fun release() { closeOutput(); finishReset(); super.release() }
    @Synchronized private fun finishReset() { reset?.complete(Unit); reset = null }
    private fun closeOutput() {
        output?.close()
        output = null
        activeDevice = 0
        playbackClock = null
        wake.release()
        PlaybackOutputStatus.lgOutput(null)
        PlaybackOutputStatus.lgDetail("等待播放")
    }
}
