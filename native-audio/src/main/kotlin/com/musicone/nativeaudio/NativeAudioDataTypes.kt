package com.musicone.nativeaudio

import androidx.annotation.Keep

@Keep
internal class ProbeResultRaw(
    val success: Boolean,
    val errorMessage: String?,
    val formatName: String?,
    val codecName: String?,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val sourceBitDepth: Int,
    val isDsd: Boolean,
    val isWavPackHybrid: Boolean,
    val dsdRateMultiplier: Int,
    val flags: Int,
    val title: String?,
    val artist: String?,
    val album: String?,
    val coverData: ByteArray?,
    val coverMime: String?,
)

@Keep
internal class OpenSessionResultRaw(
    val success: Boolean,
    val errorMessage: String?,
    val formatName: String,
    val sourceBitDepth: Int,
    val sourceSampleRate: Int,
    val isDsd: Boolean,
    val outputSampleRate: Int,
    val outputChannels: Int,
    val outputEncoding: Int,
    val outputBytesPerFrame: Int,
    val durationUs: Long,
)

/**
 * 结构化的音频源与输出规格信息。
 */
data class NativeAudioSourceInfo(
    val formatName: String,
    val sourceBitDepth: Int,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val isDsd: Boolean,
    val dsdRateMultiplier: Int = 0,
    val outputSampleRate: Int,
    val outputChannels: Int,
    val outputEncoding: Int,
    val outputBytesPerFrame: Int,
    val durationUs: Long,
) {
    val durationMs: Long get() = durationUs / 1000

    /**
     * 生成符合验收规范的规格描述文本，如：
     * "ALAC · 24bit / 96kHz"
     * "DSD64 → PCM 32bit / 192kHz"
     * "APE · 16bit / 44.1kHz"
     * "WavPack · 24bit / 192kHz"
     */
    fun formatDescription(): String {
        return if (isDsd) {
            val dsdName = if (dsdRateMultiplier > 0) "DSD$dsdRateMultiplier" else formatName
            NativeAudioOutputPolicy.dsdPcmDescription(dsdName, outputSampleRate)
        } else {
            val depthText = "${sourceBitDepth}bit"
            val rateText = formatKhz(sourceSampleRate)
            "$formatName · $depthText / $rateText"
        }
    }

    private fun formatKhz(rate: Int): String {
        return if (rate > 0) {
            val khz = java.math.BigDecimal(rate).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()
            "${khz}kHz"
        } else {
            ""
        }
    }
}
