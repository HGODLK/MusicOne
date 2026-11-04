package com.musicone

/** 两类缓存先找精确档；自定义回退按包声明，旧 DASH 自身的选择顺序保持不变。 */
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
