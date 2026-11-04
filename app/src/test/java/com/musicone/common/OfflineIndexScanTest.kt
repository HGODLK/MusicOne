package com.musicone

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineIndexScanTest {
    private fun row(id: String, key: String, pending: Boolean = false) =
        JSONObject().put("id", id).put("cacheKey", key).put("quality", "LOSSLESS")
            .put("pending", pending).put("durationMs", 240_000)

    @Test fun sharedPhysicalResourceIsCheckedOnceAndRetainsEveryAlias() {
        val records = (0 until 300).map { "reference-$it" to row("song-$it", "shared") }
        var checks = 0
        val result = scanOfflineTrackIds(records, { checks++; true },
            { error("已验证的缓存不应探测时长") }, { _, _, _ -> error("不应写回") })
        assertEquals(1, checks)
        assertEquals((0 until 300).mapTo(hashSetOf()) { "song-$it" }, result)
    }

    @Test fun firstAliasRemainsTheVerificationAuthority() {
        val first = row("first", "shared").put("trial", true)
        val second = row("second", "shared").put("trial", false)
        val result = scanOfflineTrackIds(listOf("first" to first, "second" to second), { true },
            { error("已知试听不能读取时长") }, { _, _, _ -> error("不应覆盖试听声明") })
        assertTrue(result.isEmpty())
    }

    @Test fun pendingResourceWritesBackItsWholeAliasGroupOnce() {
        val first = row("first", "shared", pending = true).put("title", "原始标题")
        val second = row("second", "shared", pending = true).put("durationMs", 30_000).put("title", "别名标题")
        var reads = 0
        var writes = 0
        val result = scanOfflineTrackIds(listOf("first-ref" to first, "second-ref" to second), { true },
            { reads++; 239_900L }, { key, aliases, full ->
                writes++
                assertEquals("shared", key)
                assertTrue(full)
                assertEquals(listOf("first-ref", "second-ref"), aliases.map { it.first })
                assertEquals(listOf("原始标题", "别名标题"), aliases.map { it.second.getString("title") })
                assertEquals(30_000L, aliases.last().second.getLong("durationMs"))
            })
        assertEquals(setOf("first", "second"), result)
        assertEquals(1, reads)
        assertEquals(1, writes)
    }

    @Test fun eachAliasKeepsItsOwnQualityEligibility() {
        val unknown = JSONObject().put("id", "unknown").put("cacheKey", "opaque")
        val known = row("known", "opaque")
        assertEquals(setOf("known"), scanOfflineTrackIds(listOf("a" to unknown, "b" to known), { true },
            { error("完整已校验缓存不应读时长") }, { _, _, _ -> error("不应写回") }))
    }

    @Test fun physicalKeysAndLegacyQualityFallbackStaySeparate() {
        val legacy = JSONObject().put("id", "legacy").put("cacheKey", "QQ:one|legacy|/F000song.flac")
        val records = listOf("legacy-ref" to legacy, "other-ref" to row("other", "other"))
        val checked = mutableListOf<String>()
        val result = scanOfflineTrackIds(records, { key -> checked += key; key != "other" },
            { error("不应读取未完成缓存") }, { _, _, _ -> error("不应写回") })
        assertEquals(listOf("QQ:one|legacy|/F000song.flac", "other"), checked)
        assertEquals(setOf("legacy"), result)
    }

    @Test fun cancellationAfterMediaProbeCannotWriteOrPublishOldResults() {
        var cancelled = false
        try {
            scanOfflineTrackIds(listOf("ref" to row("song", "shared", pending = true)), { true },
                { cancelled = true; 240_000L }, { _, _, _ -> error("取消后不能写回") },
                { if (cancelled) throw CancellationException("本轮扫描已取消") })
            fail("取消后不应返回扫描结果")
        } catch (_: CancellationException) {
            assertTrue(cancelled)
        }
    }

    @Test fun changedVerificationFieldsRejectOldProbeButTitleUpdatesRemainCompatible() {
        val original = row("song", "shared", pending = true).put("title", "旧标题")
        val basis = OfflineIndexVerificationBasis(original)
        assertEquals(basis, OfflineIndexVerificationBasis(JSONObject(original.toString()).put("title", "新标题")))
        assertNotEquals(basis, OfflineIndexVerificationBasis(JSONObject(original.toString()).put("durationMs", 30_000)))
        assertNotEquals(basis, OfflineIndexVerificationBasis(JSONObject(original.toString()).put("pending", false)))
        assertNotEquals(basis, OfflineIndexVerificationBasis(JSONObject(original.toString()).put("trial", true)))
    }
}
