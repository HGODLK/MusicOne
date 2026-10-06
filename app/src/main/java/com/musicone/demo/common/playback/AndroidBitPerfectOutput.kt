package com.musicone.demo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.Format

internal data class SystemBitPerfectSession(
    val deviceName: String,
    val sampleRate: Int,
    val bitDepth: Int,
)

/** Android 14 起先尝试厂商声明支持的系统 Bit-Perfect USB 通道。 */
internal class AndroidBitPerfectOutput(context: Context) {
    private val applicationContext = context.applicationContext
    private var active: Api34Session? = null

    fun prepare(format: Format, sourceBitDepth: Int): SystemBitPerfectSession? {
        clear()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return prepareApi34(format, sourceBitDepth)
    }

    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) clearApi34()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun prepareApi34(format: Format, sourceBitDepth: Int): SystemBitPerfectSession? {
        val sampleRate = format.sampleRate.takeIf { it > 0 } ?: return null
        val channelCount = format.channelCount.takeIf { it > 0 } ?: return null
        val encoding = format.pcmEncoding.takeIf { it != Format.NO_VALUE } ?: return null
        // 系统路径不能改写 PCM 容器；两者不一致时交给可执行整数重排的 UAC2 独占路径。
        if (encoding.bitDepth() != sourceBitDepth) return null
        val manager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isUsbOutput) ?: return null
        val selected = runCatching { manager.getSupportedMixerAttributes(device) }.getOrNull()
            ?.firstOrNull { attributes ->
                attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    attributes.format.sampleRate == sampleRate &&
                    attributes.format.channelCount == channelCount &&
                    attributes.format.encoding == encoding
            } ?: return null
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val accepted = runCatching {
            manager.setPreferredMixerAttributes(audioAttributes, device, selected)
        }.getOrDefault(false)
        if (!accepted) return null
        active = Api34Session(manager, audioAttributes, device)
        return SystemBitPerfectSession(
            deviceName = device.productName?.toString().orEmpty().ifBlank { "USB 音频设备" },
            sampleRate = sampleRate,
            bitDepth = selected.format.encoding.bitDepth(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearApi34() {
        val session = active ?: return
        active = null
        runCatching { session.manager.clearPreferredMixerAttributes(session.attributes, session.device) }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isUsbOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
        else -> false
    }

    private fun Int.bitDepth(): Int = when (this) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private data class Api34Session(
        val manager: AudioManager,
        val attributes: android.media.AudioAttributes,
        val device: AudioDeviceInfo,
    )
}
