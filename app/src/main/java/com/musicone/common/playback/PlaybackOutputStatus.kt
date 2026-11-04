package com.musicone

import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlaybackOutputSnapshot(
    val path: String,
    val device: String? = null,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val floating: Boolean = false,
    val note: String = "系统未提供 DAC 最终规格",
)

/** 区分实际使用的应用输出与硬件最终输出，不能拿文件元数据冒充输出规格。 */
internal object PlaybackOutputStatus {
    private val mutableSystem = MutableStateFlow<PlaybackOutputSnapshot?>(null)
    val system = mutableSystem.asStateFlow()
    private val mutableLg = MutableStateFlow<PlaybackOutputSnapshot?>(null)
    val lg = mutableLg.asStateFlow()
    private val mutableLgDetail = MutableStateFlow("等待播放")
    val lgDetail = mutableLgDetail.asStateFlow()

    fun systemTrack(track: AudioTrack) {
        val encoding = track.audioFormat
        mutableSystem.value = PlaybackOutputSnapshot("系统输出", track.routedDevice?.productName?.toString(),
            track.sampleRate, when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                else -> 0
            }, track.channelCount, encoding == AudioFormat.ENCODING_PCM_FLOAT)
    }
    fun clearSystem() { mutableSystem.value = null }
    fun lgOutput(snapshot: PlaybackOutputSnapshot?) { mutableLg.value = snapshot }
    fun lgDetail(message: String) { mutableLgDetail.value = message }
}

internal fun playbackOutputDescription(system: PlaybackOutputSnapshot?, lg: PlaybackOutputSnapshot?,
    usb: UsbAudioOutputSnapshot): String {
    val output = lg ?: if (usb.route in setOf(UsbAudioOutputRoute.DIRECT_UAC2, UsbAudioOutputRoute.SYSTEM_BIT_PERFECT)) {
        PlaybackOutputSnapshot(if (usb.route == UsbAudioOutputRoute.DIRECT_UAC2) {
            if (usb.converted) "UAC2 独占（兼容转换）" else "UAC2 独占"
        } else "系统 Bit-Perfect", usb.deviceName, usb.sampleRate, usb.bitDepth, usb.channelCount)
    } else system
    if (output == null) return "等待播放"
    val rate = output.sampleRate.takeIf { it > 0 }?.let {
        "${java.math.BigDecimal(it).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()}kHz"
    }
    val depth = output.bitDepth.takeIf { it > 0 }?.let { "${it}bit${if (output.floating) " Float" else ""}" }
    return listOfNotNull(output.device?.takeIf(String::isNotBlank), output.path,
        listOfNotNull(depth, rate).joinToString(" / ").takeIf(String::isNotBlank))
        .distinct()
        .joinToString(" · ")
}
