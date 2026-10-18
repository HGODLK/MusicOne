package com.musicone.nativeaudio

import androidx.media3.common.C

/**
 * 决定 PCM 编码、采样率和转换策略。
 */
object NativeAudioOutputPolicy {
    const val DEFAULT_DSD_PCM_SAMPLE_RATE = 192000
    const val FALLBACK_DSD_PCM_SAMPLE_RATE = 96000

    /**
     * 默认输出 192 kHz；能力列表仅能选择真实存在的采样率，不能把任意上限当作支持值。
     */
    fun selectDsdPcmSampleRate(supportedSampleRates: Set<Int>? = null): Int {
        if (supportedSampleRates == null || supportedSampleRates.isEmpty()) {
            return DEFAULT_DSD_PCM_SAMPLE_RATE
        }
        return if (supportedSampleRates.contains(DEFAULT_DSD_PCM_SAMPLE_RATE)) {
            DEFAULT_DSD_PCM_SAMPLE_RATE
        } else if (supportedSampleRates.contains(FALLBACK_DSD_PCM_SAMPLE_RATE)) {
            FALLBACK_DSD_PCM_SAMPLE_RATE
        } else {
            supportedSampleRates.filter { it > 0 && it <= DEFAULT_DSD_PCM_SAMPLE_RATE }.maxOrNull()
                ?: supportedSampleRates.filter { it > 0 }.minOrNull() ?: DEFAULT_DSD_PCM_SAMPLE_RATE
        }
    }

    /**
     * 计算 Media3 的 PCM 编码类型。
     */
    fun selectPcmEncoding(sourceBitDepth: Int, isDsd: Boolean): Int {
        return if (isDsd) {
            // 保留转换器生成的全部 S32 数据，不能再按 24 位截断低八位。
            C.ENCODING_PCM_32BIT
        } else if (sourceBitDepth <= 16) {
            C.ENCODING_PCM_16BIT
        } else {
            // 24-bit 或 32-bit 均装在 32-bit 容器中，保留原有有效位深记录
            C.ENCODING_PCM_32BIT
        }
    }

    fun dsdPcmDescription(name: String, sampleRate: Int = DEFAULT_DSD_PCM_SAMPLE_RATE): String {
        val rate = java.math.BigDecimal(sampleRate).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()
        return "$name → PCM 32bit / ${rate}kHz"
    }
}
