package com.musicone

/** 跨音源只传标准能力；未映射的私有档位不能当成其他音源的协议标识。 */
internal fun mainPlaybackQualityCapabilityId(quality: AudioQuality): String? =
    (if (quality.isCustom) quality.standardQuality else quality)?.name

/** 启动早期保留原身份，目录就绪后每次读取恢复当前文案及标准能力。 */
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

/** 同源上限按原始档位，跨源上限只按声明的标准能力；未映射时保留目标源首选。 */
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
