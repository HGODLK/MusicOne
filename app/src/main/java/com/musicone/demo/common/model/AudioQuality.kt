package com.musicone.demo

enum class AudioQuality(
    val label: String,
    val compactLabel: String,
    val bitRate: Int,
    val providerLevel: String,
) {
    STANDARD("标准音质", "标准", 128_000, "standard"),
    HIGHER("较高音质", "较高", 192_000, "higher"),
    EXHIGH("极高音质", "极高", 320_000, "exhigh"),
    LOSSLESS("无损音质", "无损", 999_000, "lossless"),
    HI_RES("Hi-Res", "Hi-Res", 1_999_000, "hires"),
    DOLBY("Dolby 全景声", "Dolby", 768_000, "dolby");

    fun fallbackCandidates(): List<AudioQuality> = STANDARD_AUDIO_QUALITIES
        .filter { it.ordinal <= ordinal }
        .sortedByDescending(AudioQuality::ordinal)

    fun fallbackCandidates(source: MusicSource): List<AudioQuality> = if (source == MusicSource.QQ) {
        QQ_AUDIO_QUALITIES.filter { it.ordinal <= ordinal }.sortedByDescending(AudioQuality::ordinal)
    } else {
        fallbackCandidates()
    }

    companion object {
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
)

internal data class ResolvedPlayback(
    val track: MusicTrack,
    val source: PlaybackSource,
)

internal val QQ_AUDIO_QUALITIES = listOf(
    AudioQuality.STANDARD,
    AudioQuality.EXHIGH,
    AudioQuality.LOSSLESS,
    AudioQuality.HI_RES,
    AudioQuality.DOLBY,
)

internal val STANDARD_AUDIO_QUALITIES = AudioQuality.entries.filter { it != AudioQuality.DOLBY }

internal val PLAYER_AUDIO_QUALITIES = listOf(
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
    PLAYER_AUDIO_QUALITIES.filter { it.isAvailableInPlayer(available) }

internal fun verifiedPlayerQualities(
    active: AudioQuality?,
    available: List<AudioQuality>,
): List<AudioQuality> = (available + listOfNotNull(active?.playbackEquivalent())).distinct()

internal fun playerDisplayedQuality(preferred: AudioQuality, actual: AudioQuality): AudioQuality = when {
    actual == AudioQuality.HIGHER -> AudioQuality.EXHIGH
    actual == AudioQuality.DOLBY -> AudioQuality.LOSSLESS
    else -> actual
}

internal fun AudioQuality.displayLabel(source: MusicSource): String = when (this) {
    AudioQuality.HI_RES -> "HiRes无损"
    AudioQuality.LOSSLESS, AudioQuality.DOLBY -> "FLAC无损"
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> "MP3高品质"
    AudioQuality.STANDARD -> "MP3标准"
}

internal fun AudioQuality.displayCompactLabel(source: MusicSource): String = when (this) {
    AudioQuality.HI_RES -> "HiRes"
    AudioQuality.LOSSLESS, AudioQuality.DOLBY -> "FLAC"
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> "MP3H"
    AudioQuality.STANDARD -> "MP3L"
}

internal fun AudioQuality.displayDescription(): String = when (this) {
    AudioQuality.HI_RES -> "最高24bit/192KHz"
    AudioQuality.LOSSLESS, AudioQuality.DOLBY -> "最高24bit/48KHz"
    AudioQuality.HIGHER, AudioQuality.EXHIGH -> "最高320kbps"
    AudioQuality.STANDARD -> "普通音质"
}
