package com.musicone

/** 音质探测、地址有效期和预加载调度的核心策略（纯函数，方便写单测）。 */

internal const val PLAYBACK_PRELOAD_PROBE_COUNT = 5
internal const val PLAYBACK_PRELOAD_DOWNLOAD_COUNT = 2
internal const val PLAYBACK_PRELOAD_PAUSED_DOWNLOAD_COUNT = 3
internal const val PLAYBACK_PRELOAD_NEXT_PROBE_DELAY_MS = 0L
internal const val PLAYBACK_PRELOAD_NEXT_DOWNLOAD_DELAY_MS = 280L
internal const val PLAYBACK_PRELOAD_SECOND_PROBE_DELAY_MS = 2_000L
internal const val PLAYBACK_PRELOAD_SECOND_DOWNLOAD_DELAY_MS = 2_000L
internal const val PLAYBACK_PRELOAD_THIRD_PROBE_DELAY_MS = 5_000L
internal const val PLAYBACK_PRELOAD_PROBE_GAP_MS = 1_500L
internal const val PLAYBACK_PRELOAD_LOW_BUFFER_MS = 3_000L
internal const val PLAYBACK_QUALITY_GROUP_WINDOW_MS = 12_000L
internal const val PLAYBACK_TICKET_REFRESH_MARGIN_MS = 60_000L
internal const val PLAYBACK_QUALITY_CACHE_LIMIT = 2_000
internal const val PLAYBACK_QUALITY_COMPARISON_TIMEOUT_MS = 12_000L

/** 探测结果；cacheable=false 表示有更高档超时，不能把这次低档写死进缓存。 */
internal data class PlaybackQualityProbe(
    val playback: ResolvedPlayback?,
    val cacheable: Boolean,
)

/** 音源包没返回地址有效期时，默认按 18 分钟算。 */
internal fun playbackTicketTtlMs(source: MusicSource): Long = if (source == MusicSource.LOCAL) 0L else 18 * 60_000L

/** 计算后面第 index 首歌延迟多久开始探测音质：0s、2s、5s，之后每首间隔 1.5s。 */
internal fun playbackProbeDelayMs(index: Int): Long = when {
    index <= 0 -> PLAYBACK_PRELOAD_NEXT_PROBE_DELAY_MS
    index == 1 -> PLAYBACK_PRELOAD_SECOND_PROBE_DELAY_MS
    else -> PLAYBACK_PRELOAD_THIRD_PROBE_DELAY_MS + (index - 2) * PLAYBACK_PRELOAD_PROBE_GAP_MS
}

/** 播放中只预下载后面 2 首；暂停时立刻开始预下载后面 3 首，不用等缓冲和计时。 */
internal fun playbackDownloadDelayMs(index: Int, conservative: Boolean = false, paused: Boolean = false): Long? = when {
    paused -> if (index in 0 until PLAYBACK_PRELOAD_PAUSED_DOWNLOAD_COUNT) 0L else null
    else -> when (index) {
        0 -> if (conservative) 3_000L else PLAYBACK_PRELOAD_NEXT_DOWNLOAD_DELAY_MS
        1 -> if (conservative) 10_000L else PLAYBACK_PRELOAD_SECOND_DOWNLOAD_DELAY_MS
        else -> null
    }
}

/** 获取当前音源的音质回退顺序（由音源包定义，宿主只做设备限制）。 */
internal fun playbackQualityLadder(source: MusicSource, preferred: AudioQuality): List<AudioQuality> =
    preferred.fallbackCandidates(source).distinct()

internal fun playbackQualityAllowed(source: MusicSource, quality: AudioQuality, preferred: AudioQuality): Boolean =
    quality in playbackQualityLadder(source, preferred)

/**
 * 缓存档在新首选下的起点。
 * 杜比首选会跳过 Hi-Res，缓存为 Hi-Res 时降到无损，再由回退链兜底。
 */
internal fun playbackCachedStart(cached: AudioQuality?, source: MusicSource, preferred: AudioQuality): AudioQuality? {
    if (cached == null) return null
    if (playbackQualityAllowed(source, cached, preferred)) return cached
    // 缓存的是其他编码或更高档位时，不能直接复用，按新首选的回退顺序重新探测。
    if (preferred != AudioQuality.DOLBY || cached != AudioQuality.HI_RES) return null
    return playbackQualityLadder(source, preferred).firstOrNull { it.ordinal <= cached.ordinal }
}

/** Hi-Res 或杜比首选时并发探测的另一条普通无损通道。 */
internal fun playbackParallelLanes(preferred: AudioQuality): List<AudioQuality> = when (preferred) {
    AudioQuality.HI_RES -> listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS)
    AudioQuality.DOLBY -> listOf(AudioQuality.DOLBY, AudioQuality.LOSSLESS)
    else -> emptyList()
}

/** 有更高档超时时，本次结果只能算暂用，不能当作“最高可用”写死。 */
internal fun playbackProbeCacheable(result: AudioQuality, timedOut: Set<AudioQuality>): Boolean =
    timedOut.none { it.isHigherThan(result) }

/**
 * 对比探测从首选档往下走到缓存档（含最后一档）。
 * 缓存被误判成 MP3H 时，会先补探 FLAC，而不是直接沿用 MP3H。
 */
internal fun playbackComparisonLadder(
    source: MusicSource,
    preferred: AudioQuality,
    cached: AudioQuality,
): List<AudioQuality> {
    val ladder = playbackQualityLadder(source, preferred)
    val index = if (preferred.isCustom) ladder.indexOf(cached) else ladder.indexOfFirst { it.ordinal <= cached.ordinal }
    return if (index >= 0) ladder.take(index + 1) else ladder
}

/** 并发探测返回后按档位取最高，而不是按返回先后。 */
internal fun playbackHigherQuality(first: AudioQuality?, second: AudioQuality?): AudioQuality? = when {
    first == null -> second
    second == null -> first
    second.isHigherThan(first) -> second
    else -> first
}
