package com.musicone.demo

internal data class CacheCandidate(val key: String, val bytes: Long, val touched: Long,
    val protected: Boolean = false, val active: Boolean = false)

/** 活跃文件仍计入普通用量，但只能在停止使用后淘汰。零上限表示不限制。 */
internal fun cacheEvictions(entries: List<CacheCandidate>, limitBytes: Long): List<String> {
    if (limitBytes == 0L) return emptyList()
    var excess = entries.filterNot { it.protected }.sumOf { it.bytes } - limitBytes
    return buildList {
        for (entry in entries.filter { !it.protected && !it.active }.sortedBy { it.touched }) {
            if (excess <= 0) break
            add(entry.key)
            excess -= entry.bytes
        }
    }
}

internal fun isProtectedAudioKey(key: String, protectedTracks: Set<String>) =
    key.substringBeforeLast('|') in protectedTracks

internal enum class AudioCacheClearAction { KEEP, REMOVE, AFTER_PLAYBACK }

internal fun audioCacheClearAction(protected: Boolean, active: Boolean, all: Boolean): AudioCacheClearAction = when {
    all && active -> AudioCacheClearAction.AFTER_PLAYBACK
    active || (protected && !all) -> AudioCacheClearAction.KEEP
    else -> AudioCacheClearAction.REMOVE
}
