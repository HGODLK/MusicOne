package com.musicone

import org.json.JSONObject

/** 歌曲信息可能会并发更新，用来做完整度校验的几个关键字段如果变了，就不能用旧校验结果覆盖新记录。 */
internal data class OfflineIndexVerificationBasis(val pending: Boolean, val trial: Boolean, val durationMs: Long) {
    constructor(row: JSONObject) : this(row.optBoolean("pending"), row.optBoolean("trial"), row.optLong("durationMs"))
}

/** 扫描一轮只解析一次离线索引；多条记录指向同一份物理缓存时只校验一次，同时把所有关联歌曲 ID 都记下来。 */
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
