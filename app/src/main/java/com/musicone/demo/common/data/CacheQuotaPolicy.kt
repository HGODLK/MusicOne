package com.musicone.demo

internal enum class CacheCapacity(val bytes: Long, val label: String) {
    OFF(0, "不缓存"), GB1(1_073_741_824L, "1 GB"), GB5(5_368_709_120L, "5 GB"),
    GB10(10_737_418_240L, "10 GB"), GB20(21_474_836_480L, "20 GB"), UNLIMITED(Long.MAX_VALUE, "无上限");

    companion object {
        fun fromLegacy(gb: Int) = when (gb) {
            0 -> UNLIMITED; 1 -> GB1; 10 -> GB10; 20 -> GB20; else -> GB5
        }
    }
}

internal data class CacheLimits(val total: CacheCapacity = CacheCapacity.GB5,
    val independent: CacheCapacity = CacheCapacity.OFF) {
    val maximum get() = maxOf(total.bytes, independent.bytes)
    val protectsAllPrimary get() = independent != CacheCapacity.OFF && independent.bytes >= total.bytes
    val protectionLabel get() = when {
        independent == CacheCapacity.OFF -> "一级歌曲使用总缓存，无专属保护"
        total == CacheCapacity.OFF -> "仅一级歌曲可新增缓存，已有一级歌曲全部受保护"
        protectsAllPrimary -> "全部已缓存的一级歌曲受保护"
        else -> "优先保护最近加入一级歌单的${independent.label}"
    }
}

internal data class CacheResource(val key: String, val bytes: Long, val entered: Long,
    val primaryOrder: Long? = null, val active: Boolean = false)

internal data class CacheWritePlan(val allowed: Boolean, val remove: List<String> = emptyList(), val reason: String? = null)

internal fun shouldShrinkCache(old: CacheLimits, next: CacheLimits) =
    (next.total != CacheCapacity.OFF && next.total.bytes < old.total.bytes) ||
        (next.independent != CacheCapacity.OFF && next.independent.bytes < old.independent.bytes)

/** 两个额度只取较大值；保护以整首资源为单位，不能跳过新歌去凑满旧歌。 */
internal fun protectedCacheKeys(entries: List<CacheResource>, limits: CacheLimits): Set<String> {
    if (limits.independent == CacheCapacity.OFF) return emptySet()
    val primary = entries.filter { it.primaryOrder != null && it.bytes > 0 }
    if (limits.protectsAllPrimary) return primary.mapTo(hashSetOf()) { it.key }
    var remaining = limits.independent.bytes
    return buildSet {
        for (entry in primary.sortedWith(compareByDescending<CacheResource> { it.primaryOrder }.thenBy { it.key })) {
            if (entry.bytes > remaining) break
            add(entry.key)
            remaining -= entry.bytes
        }
    }
}

/** 先模拟完整决策，不能为了最终无法接纳的请求提前删掉旧缓存。 */
internal fun cacheWritePlan(entries: List<CacheResource>, request: CacheResource, limits: CacheLimits): CacheWritePlan {
    val primary = request.primaryOrder != null
    val ceiling = if (primary) limits.maximum else limits.total.bytes
    if (ceiling == 0L) return CacheWritePlan(false, reason = if (primary) "新增缓存已关闭" else "总缓存已关闭")
    if (!primary && entries.filter { it.primaryOrder != null }.sumOf { it.bytes } >= ceiling)
        return CacheWritePlan(false, reason = "一级歌曲已占满总缓存额度")
    val merged = entries.filterNot { it.key == request.key } + request
    if (request.bytes > ceiling) return CacheWritePlan(false, reason = "单首歌曲超过缓存容量上限")
    val protected = protectedCacheKeys(merged, limits)
    var excess = merged.sumOf { it.bytes } - ceiling
    if (excess <= 0) return CacheWritePlan(true)
    val removed = mutableListOf<String>()
    val ordinary = merged.filter { it.key != request.key && !it.active && it.key !in protected }.sortedBy { it.entered }
    for (entry in ordinary) {
        if (excess <= 0) break
        removed += entry.key
        excess -= entry.bytes
    }
    // 只有一级缓存自身轮换可以淘汰旧保护歌曲，普通请求永远没有这项权限。
    val remaining = merged.filter { it.bytes > 0 && it.key !in removed && it.key != request.key }
    if (excess > 0 && primary && limits.protectsAllPrimary && remaining.all { it.key in protected }) {
        for (entry in remaining.filter { !it.active && requireNotNull(it.primaryOrder) <= requireNotNull(request.primaryOrder) }
            .sortedWith(compareBy<CacheResource> { it.primaryOrder }.thenBy { it.entered })) {
            if (excess <= 0) break
            removed += entry.key
            excess -= entry.bytes
        }
    }
    return if (excess <= 0) CacheWritePlan(true, removed)
        else CacheWritePlan(false, reason = "没有可清理空间，正在使用的缓存会在释放后重新检查")
}

/** 仅有限额度主动缩容调用；关闭缓存不自动删除历史文件。 */
internal fun cacheShrinkPlan(entries: List<CacheResource>, limits: CacheLimits): List<String> {
    if (limits.maximum == 0L) return emptyList()
    var excess = entries.sumOf { it.bytes } - limits.maximum
    val protected = protectedCacheKeys(entries, limits)
    val ordinary = entries.filter { !it.active && it.key !in protected }.sortedBy { it.entered }
    val primary = entries.filter { !it.active && it.key in protected }.sortedBy { it.primaryOrder }
    val removed = buildList {
        for (entry in ordinary + primary) {
            if (excess <= 0) break
            add(entry.key)
            excess -= entry.bytes
        }
    }
    val remaining = entries.filterNot { it.key in removed }
    var ordinaryExcess = remaining.sumOf { it.bytes } - maxOf(limits.total.bytes,
        remaining.filter { it.primaryOrder != null }.sumOf { it.bytes })
    return removed + buildList {
        for (entry in remaining.filter { it.primaryOrder == null && !it.active }.sortedBy { it.entered }) {
            if (ordinaryExcess <= 0) break
            add(entry.key)
            ordinaryExcess -= entry.bytes
        }
    }
}

internal fun cacheTrackIdentity(source: MusicSource, id: String) = "${source.name}|$id"
internal fun MusicTrack.cacheIdentity() = cacheTrackIdentity(source, id)
internal val MusicPlaylist.isPrimaryCachePlaylist get() = id == UNIFIED_PLAYLIST_ID || isAccountFavorites()
