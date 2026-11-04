package com.musicone

/** 标准常量保持原身份，自定义档以音源和原始 id 共同标识。 */
class AudioQuality private constructor(
    val name: String, val label: String, val compactLabel: String,
    val bitRate: Int, val providerLevel: String, val ordinal: Int,
    val sourceId: String? = null, val qualityId: String = name,
    val standardQuality: AudioQuality? = null, val description: String = "",
) : Comparable<AudioQuality> {
    val isCustom: Boolean get() = sourceId != null
    val capabilityQuality: AudioQuality get() = standardQuality ?: this
    override fun equals(other: Any?): Boolean = other is AudioQuality && name == other.name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name
    override fun compareTo(other: AudioQuality): Int = ordinal.compareTo(other.ordinal)

    fun fallbackCandidates(): List<AudioQuality> = if (isCustom) listOf(this) else STANDARD_AUDIO_QUALITIES
        .filter { it.ordinal <= ordinal }.sortedByDescending(AudioQuality::ordinal)

    fun fallbackCandidates(source: MusicSource): List<AudioQuality> =
        com.musicone.sourceintegration.sourceQualityFallback(source, this) ?: fallbackCandidates()

    companion object {
        val STANDARD = AudioQuality("STANDARD", "标准音质", "标准", 128_000, "standard", 0)
        val HIGHER = AudioQuality("HIGHER", "较高音质", "较高", 192_000, "higher", 1)
        val EXHIGH = AudioQuality("EXHIGH", "极高音质", "极高", 320_000, "exhigh", 2)
        val LOSSLESS = AudioQuality("LOSSLESS", "无损音质", "无损", 999_000, "lossless", 3)
        val HI_RES = AudioQuality("HI_RES", "Hi-Res", "Hi-Res", 1_999_000, "hires", 4)
        val DOLBY = AudioQuality("DOLBY", "Dolby全景", "Dolby", 768_000, "dolby", 5)
        val entries: List<AudioQuality> = listOf(STANDARD, HIGHER, EXHIGH, LOSSLESS, HI_RES, DOLBY)
        fun values(): Array<AudioQuality> = entries.toTypedArray()
        fun valueOf(name: String): AudioQuality = entries.firstOrNull { it.name == name }
            ?: decodeCustom(name) ?: throw IllegalArgumentException("未知音质：$name")
        fun parse(name: String?): AudioQuality? = name?.let { runCatching { valueOf(it) }.getOrNull() }
        fun custom(sourceId: String, id: String, label: String = id, compactLabel: String = label,
            description: String = "", standard: AudioQuality? = null, order: Int = 0): AudioQuality {
            fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
            return AudioQuality("SOURCE:${encode(sourceId)}:${encode(id)}", label, compactLabel,
                standard?.bitRate ?: 0, id, order, sourceId, id, standard, description)
        }
        private fun decodeCustom(name: String): AudioQuality? = runCatching {
            val parts = name.split(':')
            if (parts.size != 3 || parts[0] != "SOURCE") return null
            fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
            val source = decode(parts[1]); val id = decode(parts[2])
            runCatching { com.musicone.sourceintegration.sourceCatalogQualities(MusicSource.fromId(source))?.firstOrNull { it.qualityId == id } }.getOrNull()
                ?: custom(source, id, "未知音质", "未知")
        }.getOrNull()
        fun fromProvider(level: String?, bitRate: Int): AudioQuality {
            entries.firstOrNull { it.providerLevel.equals(level, ignoreCase = true) }?.let { return it }
            return STANDARD_AUDIO_QUALITIES.lastOrNull { bitRate >= it.bitRate } ?: STANDARD
        }
    }
}

internal data class PlaybackSource(
    val url: String,
    val requestedQuality: AudioQuality,
    val actualQuality: AudioQuality,
    val bitRate: Int,
    val format: String,
    val trial: Boolean,
    val actualFormat: String? = null,
    val verificationPending: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
    val cacheKey: String? = null,
    val mimeType: String? = null,
    val expiresAtEpochMs: Long? = null,
    // 仅供当前进程失败恢复；不持久化包的地址和恢复载荷。
    val sourceResource: com.musicone.source.sdk.SourcePlaybackResource? = null,
)

internal data class ResolvedPlayback(
    val track: MusicTrack,
    val source: PlaybackSource,
)

internal val STANDARD_AUDIO_QUALITIES = AudioQuality.entries.filter { it != AudioQuality.DOLBY }

internal val PLAYER_AUDIO_QUALITIES = listOf(
    AudioQuality.DOLBY,
    AudioQuality.HI_RES,
    AudioQuality.LOSSLESS,
    AudioQuality.EXHIGH,
    AudioQuality.STANDARD,
)

/** Hi-Res 使用独立音源，仅合并历史音质名称。 */
internal fun AudioQuality.playbackEquivalent(): AudioQuality = when (this) {
    AudioQuality.HIGHER -> AudioQuality.EXHIGH
    else -> this
}

internal fun AudioQuality.isAvailableInPlayer(available: List<AudioQuality>): Boolean =
    playbackEquivalent() in available.map(AudioQuality::playbackEquivalent)

internal fun visiblePlayerQualities(available: List<AudioQuality>): List<AudioQuality> =
    if (available.any { it.isCustom }) available.distinct() else PLAYER_AUDIO_QUALITIES.filter { it.isAvailableInPlayer(available) }

internal fun verifiedPlayerQualities(
    active: AudioQuality?,
    available: List<AudioQuality>,
): List<AudioQuality> = (available + listOfNotNull(active?.playbackEquivalent())).distinct()

/** 如实显示实际档位，首选 FLAC 拿到 Hi-Res 文件时不再改显示为 FLAC。 */
internal fun playerDisplayedQuality(preferred: AudioQuality, actual: AudioQuality): AudioQuality = when {
    actual == AudioQuality.HIGHER -> AudioQuality.EXHIGH
    else -> actual
}

internal fun AudioQuality.displayLabel(source: MusicSource): String =
    if (isCustom) label else source.playerPresentation?.qualityLabel(name) ?: when (this) {
    AudioQuality.HI_RES -> "HiRes无损"
    AudioQuality.LOSSLESS -> "FLAC无损"
    AudioQuality.DOLBY -> "Dolby全景"
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> "MP3高品质"
    AudioQuality.STANDARD -> "MP3标准"
    else -> label
    }

internal fun AudioQuality.displayCompactLabel(source: MusicSource): String =
    if (isCustom) compactLabel else source.playerPresentation?.qualityCompactLabel(name) ?: when (this) {
    AudioQuality.HI_RES -> "HiRes"
    AudioQuality.LOSSLESS -> "FLAC"
    AudioQuality.DOLBY -> "Dolby"
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> "MP3H"
    AudioQuality.STANDARD -> "MP3L"
    else -> compactLabel
    }

internal fun AudioQuality.displayDescription(): String = if (isCustom) description else when (this) {
    AudioQuality.HI_RES -> "最高24bit/192KHz"
    AudioQuality.LOSSLESS -> "最高24bit/48KHz"
    AudioQuality.DOLBY -> "杜比音源 · 输出效果取决于设备"
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> "最高320kbps"
    AudioQuality.STANDARD -> "普通音质"
    else -> description
}

/** 自定义档只按声明的回退关系比较，能力映射不能合并两个真实档位。 */
internal fun AudioQuality.isHigherThan(other: AudioQuality): Boolean {
    if (this == other) return false
    if (!isCustom && !other.isCustom) return ordinal > other.ordinal
    if (sourceId == null || sourceId != other.sourceId) return false
    val source = MusicSource.fromId(sourceId)
    if (com.musicone.sourceintegration.sourceCatalogQualities(source)?.none { it == this } != false) return false
    return other in fallbackCandidates(source).drop(1)
}
