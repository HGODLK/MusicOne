package com.musicone

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

/** 先按音频原始规格打开 USB 流，失败后再尝试兼容格式；每次失败都要释放流，避免影响下一次尝试。 */
internal fun createDirectUsbPcmOutput(
    device: UsbAudioDevice,
    info: UsbAudioDeviceInfo,
    format: Format,
    sourceBits: Int,
    beforeStart: (UsbAudioStreamProfile) -> Unit = { device.maximizeHardwareVolume(it) },
): DirectUsbPcmOutput? {
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
                start = {
                    // 等采样率时钟生效后再设硬件音量，按当前开关决定拉满还是设成目标音量。
                    beforeStart(profile)
                    stream.start()
                })
            if (accepted) return DirectUsbPcmOutput(stream, profile, plan.sampleRate, resampler, plan.converted)
        } catch (error: Exception) {
            android.util.Log.w("MusicOneUsbAudio", "USB 输出候选配置失败", error)
        } catch (_: UnsatisfiedLinkError) {
            // 如果当前架构没有编进原生转换库，就回退系统默认输出，不要假装转换成功。
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
