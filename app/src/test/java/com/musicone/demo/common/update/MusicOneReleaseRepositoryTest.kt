package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicOneReleaseRepositoryTest {
    private fun release(version: String, draft: Boolean = false, prerelease: Boolean = false): JSONObject {
        val tag = "v$version"
        val assetName = "MusicOne-$version.apk"
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("assets", JSONArray().put(JSONObject().put("name", assetName).put("state", "uploaded")
                .put("size", 123).put("browser_download_url", "$MUSIC_ONE_DOWNLOAD_ROOT$tag/$assetName")))
    }

    private fun response(rows: JSONArray) = PlatformHttpResponse(200, rows.toString().toByteArray(), emptyMap())

    @Test fun prereleasesAreIncludedButDraftsAndUnavailableApksAreExcluded() {
        val draft = release("9.0.0", draft = true)
        val missing = release("8.0.0").put("assets", JSONArray())
        val uploading = release("7.0.0").also {
            it.getJSONArray("assets").getJSONObject(0).put("state", "new")
        }
        val wrongDownload = release("6.0.0").also {
            it.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", "https://example.com/MusicOne-6.0.0.apk")
        }
        val rows = JSONArray().put(release("0.1.9")).put(draft).put(missing).put(uploading)
            .put(wrongDownload).put(release("0.1.10", prerelease = true))
        val latest = MusicOneReleaseRepository { response(rows) }.latest()
        assertEquals("0.1.10", latest?.version?.label)
        assertEquals("${MUSIC_ONE_DOWNLOAD_ROOT}v0.1.10/MusicOne-0.1.10.apk", latest?.downloadUrl)
    }

    @Test fun highestVersionCanBeOnALaterPageRegardlessOfPublishingOrder() {
        val first = JSONArray()
        repeat(100) { first.put(release("0.1.0")) }
        val visited = mutableListOf<Int>()
        val latest = MusicOneReleaseRepository { page ->
            visited += page
            response(if (page == 1) first else JSONArray().put(release("0.2.0")))
        }.latest()
        assertEquals(listOf(1, 2), visited)
        assertEquals("0.2.0", latest?.version?.label)
    }

    @Test fun anEmptyPublishedListHasNoDownloadCandidate() {
        assertNull(MusicOneReleaseRepository { response(JSONArray()) }.latest())
    }

    @Test fun releasesWithVaryingChineseNicknameApksAreParsedAndExtracted() {
        // Tag 和 附件均带代号（四字代号）
        val row1 = JSONObject().put("tag_name", "v0.2.1-麦乐鸡块").put("draft", false)
            .put("assets", JSONArray().put(JSONObject().put("name", "MusicOne-0.2.1-麦乐鸡块.apk")
                .put("state", "uploaded").put("size", 100)
                .put("browser_download_url", "${MUSIC_ONE_DOWNLOAD_ROOT}v0.2.1-麦乐鸡块/MusicOne-0.2.1-麦乐鸡块.apk")))

        val latest1 = MusicOneReleaseRepository { response(JSONArray().put(row1)) }.latest()
        assertNotNull(latest1)
        assertEquals("0.2.1-麦乐鸡块", latest1?.version?.label)
        assertEquals("0.2.1", latest1?.version?.coreLabel)
        assertEquals("麦乐鸡块", latest1?.version?.nickname)
        assertEquals("${MUSIC_ONE_DOWNLOAD_ROOT}v0.2.1-麦乐鸡块/MusicOne-0.2.1-麦乐鸡块.apk", latest1?.downloadUrl)

        // Tag 为纯数字，附件带三字代号
        val row2 = JSONObject().put("tag_name", "v0.2.2").put("draft", false)
            .put("assets", JSONArray().put(JSONObject().put("name", "MusicOne-0.2.2-冰美式.apk")
                .put("state", "uploaded").put("size", 100)
                .put("browser_download_url", "${MUSIC_ONE_DOWNLOAD_ROOT}v0.2.2/MusicOne-0.2.2-冰美式.apk")))

        val latest2 = MusicOneReleaseRepository { response(JSONArray().put(row2)) }.latest()
        assertNotNull(latest2)
        assertEquals("0.2.2-冰美式", latest2?.version?.label)
        assertEquals("0.2.2", latest2?.version?.coreLabel)
        assertEquals("冰美式", latest2?.version?.nickname)

        // 排除 debugrelease 附件与版本前缀误匹配
        val debugRow = JSONObject().put("tag_name", "v0.2.3").put("draft", false)
            .put("assets", JSONArray().put(JSONObject().put("name", "MusicOne-0.2.3-debugrelease.apk")
                .put("state", "uploaded").put("size", 100)
                .put("browser_download_url", "${MUSIC_ONE_DOWNLOAD_ROOT}v0.2.3/MusicOne-0.2.3-debugrelease.apk")))
        assertNull(MusicOneReleaseRepository { response(JSONArray().put(debugRow)) }.latest())

        val prefixRow = JSONObject().put("tag_name", "v0.2.1").put("draft", false)
            .put("assets", JSONArray().put(JSONObject().put("name", "MusicOne-0.2.10.apk")
                .put("state", "uploaded").put("size", 100)
                .put("browser_download_url", "${MUSIC_ONE_DOWNLOAD_ROOT}v0.2.1/MusicOne-0.2.10.apk")))
        assertNull(MusicOneReleaseRepository { response(JSONArray().put(prefixRow)) }.latest())

        // 真实 GitHub 数据：Tag 包含中文时，GitHub 的 browser_download_url 会进行 URL 编码
        val encodedRow = JSONObject().put("tag_name", "v78.78.78-大皇堡").put("draft", false)
            .put("body", "## 仅供更新测试使用\n\n- 本版本为测试版本")
            .put("assets", JSONArray().put(JSONObject().put("name", "MusicOne-78.78.78.apk")
                .put("state", "uploaded").put("size", 101097729)
                .put("browser_download_url", "${MUSIC_ONE_DOWNLOAD_ROOT}v78.78.78-%E5%A4%A7%E7%9A%87%E5%A0%A1/MusicOne-78.78.78.apk")))

        val latestEncoded = MusicOneReleaseRepository { response(JSONArray().put(encodedRow)) }.latest()
        assertNotNull(latestEncoded)
        assertEquals("78.78.78-大皇堡", latestEncoded?.version?.label)
        assertEquals("78.78.78", latestEncoded?.version?.coreLabel)
        assertEquals("大皇堡", latestEncoded?.version?.nickname)
        assertEquals("${MUSIC_ONE_DOWNLOAD_ROOT}v78.78.78-%E5%A4%A7%E7%9A%87%E5%A0%A1/MusicOne-78.78.78.apk", latestEncoded?.downloadUrl)
        assertEquals("## 仅供更新测试使用\n\n- 本版本为测试版本", latestEncoded?.body)
    }

    @Test fun aFailedLaterPageDoesNotReturnAPartialResult() {
        val first = JSONArray()
        repeat(100) { first.put(release("0.1.0")) }
        try {
            MusicOneReleaseRepository { page ->
                if (page == 1) response(first) else error("网络暂时不可用")
            }.latest()
            fail("分页失败必须保留原缓存，不能把部分列表当作成功")
        } catch (expected: IllegalStateException) {
            assertEquals("网络暂时不可用", expected.message)
        }
    }
}
