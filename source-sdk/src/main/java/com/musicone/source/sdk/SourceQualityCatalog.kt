package com.musicone.source.sdk

/** 可选音质目录，由 SourceMediaPlayback 的实现同时实现；旧包无需实现。 */
interface SourceQualityCatalog {
    val qualityCatalog: List<SourceQualityDefinition>
    val defaultCatalogQualityId: String
    /** 提供明确回退顺序；宿主先尝试首选并去重，未列出的其他档位不会自动加入。 */
    fun catalogFallbackQualityIds(preferredId: String): List<String>
}

/** id 在同一音源内保持稳定；多个 id 可以对应同一个标准能力档。 */
data class SourceQualityDefinition(
    val id: String,
    val label: String = "",
    val compactLabel: String = label,
    val description: String = "",
    val standardQualityId: String? = null,
)
