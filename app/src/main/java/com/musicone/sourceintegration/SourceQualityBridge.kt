package com.musicone.sourceintegration

import com.musicone.*
import com.musicone.source.sdk.*

internal fun sourceQualityCatalog(source: MusicSource): SourceQualityCatalog? =
    SourcePackageController.current()?.running(source.id)?.entry?.mediaPlayback as? SourceQualityCatalog

internal fun sourceCatalogQualities(source: MusicSource): List<AudioQuality>? =
    sourceQualityCatalog(source)?.let { sourceCatalogQualities(source, it) }

internal fun sourceCatalogQualities(source: MusicSource, catalog: SourceQualityCatalog): List<AudioQuality> {
    val definitions = catalog.qualityCatalog
    require(definitions.isNotEmpty() && definitions.all { it.id.isNotBlank() }) { "音源音质目录和标识不能为空" }
    require(definitions.map { it.id }.distinct().size == definitions.size) { "音源音质目录包含重复标识" }
    require(definitions.any { it.id == catalog.defaultCatalogQualityId }) { "音源默认音质未列入目录" }
    require(definitions.all { it.standardQualityId?.let { mapped -> sourceAudioQuality(mapped) != null } ?: true }) { "音源音质标准映射无效" }
    return definitions.mapIndexed { index, definition ->
        val standard = sourceAudioQuality(definition.standardQualityId.orEmpty())
        val label = definition.label.ifBlank { standard?.label ?: if (definitions.size == 1) "默认音质" else "音质 ${index + 1}" }
        val compact = definition.compactLabel.ifBlank {
            if (definition.label.isBlank() && standard != null) standard.compactLabel else label
        }
        AudioQuality.custom(source.id, definition.id, label, compact,
            definition.description, standard, definitions.size - index)
    }
}

internal fun sourceAudioQuality(id: String, source: MusicSource): AudioQuality? {
    val catalog = sourceCatalogQualities(source)
    catalog?.firstOrNull { it.qualityId == id || it.name == id }?.let { return it }
    AudioQuality.parse(id)?.takeIf { it.sourceId == source.id }?.let { return it }
    return if (catalog != null) id.takeIf(String::isNotBlank)?.let { AudioQuality.custom(source.id, it, "未知音质", "未知") }
    else sourceAudioQuality(id)
}

internal fun sourceQualityFallback(source: MusicSource, preferred: AudioQuality): List<AudioQuality>? {
    val service = SourcePackageController.current()?.running(source.id)?.entry?.mediaPlayback ?: return null
    val catalog = service as? SourceQualityCatalog
    if (catalog != null) {
        return sourceCatalogFallback(source, catalog, preferred)
    }
    return service.fallbackQualityIds(preferred.name).mapNotNull(::sourceAudioQuality)
}

internal fun sourceCatalogFallback(source: MusicSource, catalog: SourceQualityCatalog, preferred: AudioQuality): List<AudioQuality> {
    val qualities = sourceCatalogQualities(source, catalog)
    val selected = qualities.firstOrNull { it == preferred }
        ?: qualities.firstOrNull { it.standardQuality == preferred }
        ?: qualities.first { it.qualityId == catalog.defaultCatalogQualityId }
    val fallback = catalog.catalogFallbackQualityIds(selected.qualityId)
    require(fallback.all { id -> qualities.any { it.qualityId == id } }) { "音源音质回退包含未声明标识" }
    return (listOf(selected.qualityId) + fallback).distinct().map { id -> qualities.first { it.qualityId == id } }
}

internal fun sourceCatalogPreferred(source: MusicSource, catalog: SourceQualityCatalog,
    saved: AudioQuality?, normalizedId: String? = null): AudioQuality {
    val values = sourceCatalogQualities(source, catalog)
    return values.firstOrNull { it.qualityId == normalizedId }
        ?: values.firstOrNull { it == saved }
        ?: values.firstOrNull { saved != null && it.standardQuality == saved }
        ?: values.first { it.qualityId == catalog.defaultCatalogQualityId }
}
