package com.musicone.demo

import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

internal const val MUSIC_ONE_DOWNLOAD_ROOT = "https://github.com/HGODLK/MusicOne/releases/download/"

internal data class MusicOneRelease(val version: MusicOneVersion, val downloadUrl: String, val body: String = "")

/** 使用发布列表以包含预发布版本；分页完成后才更新缓存，避免部分失败被当作没有更新。 */
internal class MusicOneReleaseRepository(
    private val fetchPage: (Int) -> PlatformHttpResponse = { page ->
        PlatformHttp.get(updateReleasePageUrl(page), headers = mapOf(
            "Accept" to "application/json",
        ))
    },
) {
    fun latest(): MusicOneRelease? {
        var latest: MusicOneRelease? = null
        var page = 1
        do {
            val response = fetchPage(page)
            check(response.status == 200) { "更新检查未返回有效发布列表" }
            val releases = JSONArray(response.text)
            for (index in 0 until releases.length()) {
                val release = parseMusicOneRelease(releases.optJSONObject(index) ?: continue) ?: continue
                if (latest == null || release.version > latest.version) latest = release
            }
            page++
        } while (releases.length() == 100)
        return latest
    }
}

internal fun parseMusicOneRelease(value: JSONObject): MusicOneRelease? {
    if (value.optBoolean("draft", true)) return null
    val tag = value.optString("tag_name")
    val body = value.optString("body")
    val versionFromTag = MusicOneVersion.parse(tag) ?: return null
    val coreLabel = versionFromTag.coreLabel
    val assets = value.optJSONArray("assets") ?: return null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        val assetName = asset.optString("name")
        val state = asset.optString("state")
        val size = asset.optLong("size")
        val downloadUrl = asset.optString("browser_download_url")
        val expectedUrl = "$MUSIC_ONE_DOWNLOAD_ROOT$tag/$assetName"
        val decodedDownloadUrl = runCatching { URLDecoder.decode(downloadUrl, "UTF-8") }.getOrDefault(downloadUrl)

        if (state == "uploaded" && size > 0 &&
            (downloadUrl == expectedUrl || decodedDownloadUrl == expectedUrl) &&
            isValidReleaseApk(assetName, coreLabel)) {
            // 若 Tag 未包含代号，则尝试从附件名中提取（例如 MusicOne-0.2.0-麦乐鸡块.apk）
            val resolvedVersion = if (versionFromTag.nickname != null) {
                versionFromTag
            } else {
                val assetNickname = extractAssetNickname(assetName, coreLabel)
                if (assetNickname != null) versionFromTag.copy(nickname = assetNickname) else versionFromTag
            }
            return MusicOneRelease(resolvedVersion, downloadUrl, body)
        }
    }
    return null
}

private fun isValidReleaseApk(assetName: String, coreLabel: String): Boolean {
    if (assetName == "MusicOne-$coreLabel.apk") return true
    val prefix = "MusicOne-$coreLabel-"
    return assetName.startsWith(prefix) && assetName.endsWith(".apk") && !assetName.endsWith("-debugrelease.apk")
}

private fun extractAssetNickname(assetName: String, coreLabel: String): String? {
    val prefix = "MusicOne-$coreLabel-"
    if (assetName.startsWith(prefix) && assetName.endsWith(".apk")) {
        val raw = assetName.removePrefix(prefix).removeSuffix(".apk").trim()
        return raw.removeSuffix("-debugrelease").takeIf { it.isNotBlank() }
    }
    return null
}

