package com.musicone

import org.json.JSONObject

/** 标题等资料可并发更新，核验依据变化后不能用旧探测结果覆盖新状态。 */
internal data class OfflineIndexVerificationBasis(val pending: Boolean, val trial: Boolean, val durationMs: Long) {
    constructor(row: JSONObject) : this(row.optBoolean("pending"), row.optBoolean("trial"), row.optLong("durationMs"))
}

/** 一轮只解析一次索引；同一物理缓存保留首条校验依据和所有歌曲别名。 */
internal fun scanOfflineTrackIds(
    records: List<Pair<String, JSONObject>>,
    completeBytes: (String) -> Boolean,
    readDuration: (String) -> Long?,
    saveVerification: (String, List<Pair<String, JSONObject>>, Boolean) -> Unit,
    checkActive: () -> Unit = {},
): Set<String> {
    val byKey = linkedMapOf<String, MutableList<Pair<String, JSONObject>>>()
    records.forEach { record ->
        checkActive()
        val key = record.second.optString("cacheKey", record.first)
        byKey.getOrPut(key) { mutableListOf() }.add(record)
    }
    val result = hashSetOf<String>()
    byKey.forEach { (key, aliases) ->
        checkActive()
        val candidates = aliases.filter {
            checkActive()
            offlineRecordQuality(key, it.second) != null
        }
        if (candidates.isEmpty()) return@forEach
        val complete = completeBytes(key)
        checkActive()
        val verified = verifyOfflineRecord(aliases.first().second, complete, {
            checkActive()
            readDuration(key).also { checkActive() }
        }, { full ->
            checkActive()
            saveVerification(key, aliases, full)
        })
        checkActive()
        if (verified) candidates.forEach { result.add(it.second.optString("id")) }
    }
    return result
}
