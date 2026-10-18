package com.musicone.demo

import androidx.media3.common.Format
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDeviceInfo
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.UsbAudioStreamProfile
import com.musicone.nativeaudio.NativePcmResampler

internal data class DirectUsbPcmOutput(
    val stream: UsbAudioStream, val profile: UsbAudioStreamProfile, val sampleRate: Int,
    val resampler: NativePcmResampler?, val converted: Boolean,
)

/** 先尝试原规格，再尝试兼容转换；每次失败均释放候选流，不让旧配置污染下一次。 */
internal fun createDirectUsbPcmOutput(device: UsbAudioDevice, info: UsbAudioDeviceInfo,
    format: Format, sourceBits: Int): DirectUsbPcmOutput? {
    val plans = usbPcmOutputPlans(format.sampleRate, sourceBits) { rate, bits ->
        device.findStreamProfile(rate, format.channelCount, bits)
    }
    for (plan in plans) {
        var resampler: NativePcmResampler? = null
        var candidate: UsbAudioStream? = null
        var accepted = false
        try {
            if (plan.sampleRate != format.sampleRate) {
                resampler = NativePcmResampler(format.sampleRate, plan.sampleRate, format.channelCount, format.pcmEncoding)
            }
            val profile = plan.profile
            val stream = UsbAudioStream(info.fd, profile.interfaceId, profile.endpointOutAddress,
                profile.endpointFeedbackAddress, plan.sampleRate, format.channelCount, profile.bitDepth,
                profile.subslotSizeBytes, profile.maxPacketSize, profile.packetsPerSecond, profile.busIntervalsPerSecond)
            candidate = stream
            if (!stream.isReady) continue
            accepted = configureUsbPcmOutput(plan.sampleRate,
                deactivate = { device.setAltSetting(0, profile.interfaceId) },
                setSampleRate = { device.setSampleRate(it, profile) },
                selectProfile = {
                    device.setAltSetting(profile.altSetting, profile.interfaceId).also {
                        if (it) Thread.sleep(50)
                    }
                },
                readSampleRate = { device.readSampleRate(profile) },
                clockValid = { device.awaitClockValid(profile) },
                start = stream::start)
            if (accepted) return DirectUsbPcmOutput(stream, profile, plan.sampleRate, resampler, plan.converted)
        } catch (error: Exception) {
            android.util.Log.w("MusicOneUsbAudio", "USB 输出候选配置失败", error)
        } catch (_: UnsatisfiedLinkError) {
            // 没有原生转换库的 ABI 仍保留系统兼容输出，不能装作已转换成功。
        } finally {
            if (!accepted) {
                try {
                    candidate?.stop()
                    candidate?.release()
                } finally { resampler?.close() }
                device.setAltSetting(0, plan.profile.interfaceId)
            }
        }
    }
    return null
}
