package com.musicone.demo

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

/** 按物理缓存键去重；同曲跨歌单的引用不能重复计算占用。 */
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
