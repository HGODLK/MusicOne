package com.musicone.demo

import androidx.media3.common.C
import androidx.media3.common.Format
import com.decent.usbaudio.UsbAudioStreamProfile

internal data class UsbPcmOutputPlan(val sampleRate: Int, val profile: UsbAudioStreamProfile, val converted: Boolean)

/** 可原规格输出时保留样本；兼容路径固定请求 32bit／192kHz，不能伪造设备能力。 */
internal fun usbPcmOutputPlans(sampleRate: Int, bitDepth: Int,
    findProfile: (Int, Int) -> UsbAudioStreamProfile?): Sequence<UsbPcmOutputPlan> = sequence {
    findProfile(sampleRate, bitDepth)?.let { yield(UsbPcmOutputPlan(sampleRate, it, false)) }
    findProfile(192000, 32)?.let { yield(UsbPcmOutputPlan(192000, it, true)) }
}.distinctBy { it.sampleRate to it.profile }

/** 输出容器不能覆盖输入音轨的有效位深；原生 PCM 的显式标签优先。 */
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

/** 先停止接口再改时钟；回读不匹配时不能启动独占流并虚报配置成功。 */
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
