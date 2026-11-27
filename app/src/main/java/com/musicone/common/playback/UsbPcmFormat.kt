package com.musicone

import androidx.media3.common.C
import androidx.media3.common.Format
import com.decent.usbaudio.UsbAudioStreamProfile

internal data class UsbPcmOutputPlan(val sampleRate: Int, val profile: UsbAudioStreamProfile, val converted: Boolean)

/** 生成 USB 输出候选方案：优先用音频原规格，设备不支持时再回退到 32bit/192kHz 兼容模式。 */
internal fun usbPcmOutputPlans(sampleRate: Int, bitDepth: Int,
    findProfile: (Int, Int) -> UsbAudioStreamProfile?): Sequence<UsbPcmOutputPlan> = sequence {
    findProfile(sampleRate, bitDepth)?.let { yield(UsbPcmOutputPlan(sampleRate, it, false)) }
    findProfile(192000, 32)?.let { yield(UsbPcmOutputPlan(192000, it, true)) }
}.distinctBy { it.sampleRate to it.profile }

/** 确定音源真实位深：以原生 PCM 标注的位深为准，不被解码器输出的容器位深覆盖。 */
internal fun usbPcmSourceBitDepth(output: Format, sourceBitDepth: Int): Int {
    val containerDepth = when (output.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> sourceBitDepth
    }
    val depth = if (output.label?.startsWith("effective-depth:") == true) {
        sourceAudioBitDepth(output)
    } else sourceBitDepth
    return minOf(depth, containerDepth)
}

/** 配置 USB 采样率：先停流再改时钟，最后回读确认一致才算成功。 */
internal fun configureUsbPcmOutput(
    sampleRate: Int,
    deactivate: () -> Boolean,
    setSampleRate: (Int) -> Boolean,
    selectProfile: () -> Boolean,
    readSampleRate: () -> Int,
    start: () -> Boolean,
    clockValid: () -> Boolean = { true },
): Boolean {
    if (!deactivate()) return false
    setSampleRate(sampleRate)
    if (!selectProfile()) return false
    val actualRate = readSampleRate()
    // 只读固定时钟允许 SET_CUR 失败，但必须回读确认正好是目标采样率。
    if (actualRate != sampleRate) return false
    return clockValid() && start()
}
