package com.musicone.demo

import org.json.JSONObject

internal class KugouQualityUnavailable(quality: AudioQuality) :
    PlatformApiException("当前账号没有${quality.displayLabel(MusicSource.KUGOU)}音源")

internal fun JSONObject.requireKugouPrivilegeItem(quality: AudioQuality): JSONObject {
    val item = kugouPrivilegeItem(quality.kugouQuality())
    if (item != null && item.hasKugouPlaybackUrl()) return item
    val hasPlayableAlternative = listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS, AudioQuality.HI_RES)
        .asSequence()
        .filterNot { it == quality }
        .mapNotNull { kugouPrivilegeItem(it.kugouQuality()) }
        .any(JSONObject::hasKugouPlaybackUrl)
    if (hasPlayableAlternative) throw KugouQualityUnavailable(quality)
    throw kugouMissingPlaybackUrl(this, "酷狗权限接口未返回可用音源")
}

internal fun JSONObject.kugouPrivilegeItem(quality: String): JSONObject? {
    val data = optJSONObject("data") ?: this
    val candidates = buildList {
        add(data)
        data.optJSONArray("relate_goods")?.let { goods ->
            for (index in 0 until goods.length()) goods.optJSONObject(index)?.let(::add)
        }
        // 部分权限响应以档位名作为对象键，子对象本身不再携带 quality。
        listOf("128", "320", "flac", "high").forEach { name ->
            data.optJSONObject(name)?.let { item ->
                add(JSONObject(item.toString()).put("quality", name))
            }
        }
    }
    return candidates.firstOrNull { item ->
        (item.optString("quality").equals(quality, ignoreCase = true) ||
            item.optString("quality").kugouQualityFromName()?.kugouQuality() == quality ||
            item.optInt("level").kugouQualityFromLevel()?.kugouQuality() == quality) &&
            item.optInt("status", 1) == 1 && item.optInt("_errno", 0) == 0 &&
            item.hasKugouPlaybackUrl()
    }
}

internal fun JSONObject.kugouPlayableQualityNames(): List<String> =
    listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS, AudioQuality.HI_RES)
        .filter { kugouPrivilegeItem(it.kugouQuality())?.hasKugouPlaybackUrl() == true }
        .map(AudioQuality::name)

internal fun String.kugouQualityFromName(): AudioQuality? = when (trim().lowercase()) {
    "128", "mp3", "standard" -> AudioQuality.STANDARD
    "320", "hq", "exhigh" -> AudioQuality.EXHIGH
    "flac", "lossless", "sq" -> AudioQuality.LOSSLESS
    "high", "hires", "hi-res", "res" -> AudioQuality.HI_RES
    else -> null
}

private fun JSONObject.hasKugouPlaybackUrl(): Boolean = pickKugouUrl().isNotBlank() ||
    optJSONObject("info")?.pickKugouUrl()?.isNotBlank() == true
