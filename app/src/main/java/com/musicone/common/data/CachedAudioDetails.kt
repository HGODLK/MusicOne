package com.musicone

internal data class CachedAudioResource(
    val key: String,
    val identity: String,
    val quality: AudioQuality?,
    val bytes: Long,
)

internal data class CachedAudioDetails(
    val qualities: List<AudioQuality>,
    val bytes: Long,
    val hasUnknownQuality: Boolean,
)

/** 按实际缓存文件去重统计，同一首歌被多个歌单引用只算一次空间占用。 */
internal fun cachedAudioDetails(resources: List<CachedAudioResource>): Map<String, CachedAudioDetails> = resources
    .filter { it.bytes > 0 }
    .distinctBy { it.identity to it.key }
    .groupBy { it.identity }
    .mapValues { (_, rows) ->
        CachedAudioDetails(
            qualities = rows.mapNotNull { it.quality }.distinct().sortedByDescending { it.ordinal },
            bytes = rows.sumOf { it.bytes },
            hasUnknownQuality = rows.any { it.quality == null },
        )
    }
