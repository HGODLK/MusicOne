package com.musicone

/** 离线选歌优先匹配首选音质；自定义音质按音源声明顺序回退，旧 DASH 保持原回退顺序。 */
internal fun selectOfflinePlayback(
    preferred: AudioQuality,
    ordinary: List<ResolvedPlayback>,
    dash: ResolvedPlayback?,
    fallback: () -> List<AudioQuality>,
): ResolvedPlayback? {
    val candidates = listOfNotNull(dash) + ordinary
    candidates.firstOrNull { it.source.actualQuality == preferred }?.let { return it }
    if (preferred.isCustom) {
        return fallback().firstNotNullOfOrNull { quality ->
            candidates.firstOrNull { it.source.actualQuality == quality }
        }
    }
    return dash ?: ordinary.maxByOrNull { it.source.actualQuality.ordinal }
}
