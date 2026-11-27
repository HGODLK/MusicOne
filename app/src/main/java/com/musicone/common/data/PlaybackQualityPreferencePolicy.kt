package com.musicone

/** 跨音源传递音质偏好时只传标准档位，私有自定义档位不能直接塞给别的音源。 */
internal fun mainPlaybackQualityCapabilityId(quality: AudioQuality): String? =
    (if (quality.isCustom) quality.standardQuality else quality)?.name

/** 启动早期音源目录还没就绪时先保留原保存的音质 ID，就绪后再补全显示文案和标准映射。 */
internal fun refreshMobileQuality(
    saved: AudioQuality?,
    catalog: (String) -> List<AudioQuality>? = { sourceId ->
        runCatching {
            com.musicone.sourceintegration.sourceCatalogQualities(MusicSource.fromId(sourceId))
        }.getOrNull()
    },
): AudioQuality? {
    if (saved == null || !saved.isCustom) return saved
    val sourceId = requireNotNull(saved.sourceId)
    val values = catalog(sourceId) ?: return saved
    return values.firstOrNull { it.qualityId == saved.qualityId }
        ?: AudioQuality.custom(sourceId, saved.qualityId, "未知音质", "未知")
}

/** 流量音质上限：同音源直接按原档位卡，跨音源按标准档位卡；映射不上就保留原首选。 */
internal fun mobileLimitedQuality(preferred: AudioQuality, limit: AudioQuality?, sourceId: String?,
    fallback: () -> List<AudioQuality>): AudioQuality {
    if (limit == null) return preferred
    return when {
        preferred.isCustom && sourceId != null && limit.sourceId == sourceId ->
            limit.takeIf { it in fallback() } ?: preferred
        preferred.isCustom && sourceId != null -> (limit.standardQuality ?: limit.takeUnless { it.isCustom })?.let { ceiling ->
            fallback().firstOrNull { it.standardQuality?.let { mapped -> mapped.ordinal <= ceiling.ordinal } == true }
        } ?: preferred
        limit.isCustom -> limit.standardQuality?.takeIf { it.ordinal < preferred.ordinal } ?: preferred
        else -> limit.takeIf { it.ordinal < preferred.ordinal } ?: preferred
    }
}
