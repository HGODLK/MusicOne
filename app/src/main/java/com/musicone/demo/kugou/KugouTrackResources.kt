package com.musicone.demo

import org.json.JSONObject

/** 云歌单主 hash 属于收藏时的档位，不能一律当成标准音质。 */
internal fun JSONObject.kugouQualityHashes(): Map<AudioQuality, String> = buildMap {
    fun hash(item: JSONObject, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        item.optString(name).trim().takeIf { it.isNotEmpty() && it.any { char -> char != '0' } && it != "null" }
    }
    fun resourceQuality(item: JSONObject): AudioQuality? =
        item.optInt("level").kugouQualityFromLevel() ?: when (item.optString("quality").lowercase()) {
            "128" -> AudioQuality.STANDARD
            "320" -> AudioQuality.EXHIGH
            "flac" -> AudioQuality.LOSSLESS
            "high" -> AudioQuality.HI_RES
            else -> normalizedKugouBitrate(item.optInt("bitrate")).let { rate ->
                when {
                    rate >= 500_000 -> AudioQuality.LOSSLESS
                    rate >= 256_000 -> AudioQuality.EXHIGH
                    rate > 0 -> AudioQuality.STANDARD
                    else -> null
                }
            }
        }
    val explicit = listOf(
        AudioQuality.STANDARD to arrayOf("FileHash", "filehash", "file_hash"),
        AudioQuality.EXHIGH to arrayOf("HQFileHash", "hqfilehash", "hq_hash", "320hash"),
        AudioQuality.LOSSLESS to arrayOf("SQFileHash", "sqfilehash", "sq_hash", "sqhash"),
        AudioQuality.HI_RES to arrayOf("ResFileHash", "resfilehash", "res_hash"),
    )
    val root = this@kugouQualityHashes
    hash(root, "hash", "Hash")?.let { put(resourceQuality(root) ?: AudioQuality.STANDARD, it) }
    root.optJSONArray("relate_goods")?.let { goods ->
        for (index in 0 until goods.length()) {
            val item = goods.optJSONObject(index) ?: continue
            val quality = resourceQuality(item) ?: continue
            hash(item, "hash", "FileHash")?.let { put(quality, it) }
        }
    }
    explicit.forEach { (quality, names) -> hash(root, *names)?.let { value ->
        val actual = if (quality == AudioQuality.STANDARD &&
            value.equals(hash(root, "hash", "Hash"), ignoreCase = true)) resourceQuality(root) ?: quality else quality
        put(actual, value)
    } }
    val trans = root.optJSONObject("trans_param") ?: root.optJSONObject("TransParam")
    trans?.let {
        // getOrPut 为标准库内联实现；Map#putIfAbsent 需要 API 24，minSdk 23 上会崩。
        hash(it, "ogg_128_hash")?.let { value -> getOrPut(AudioQuality.STANDARD) { value } }
        hash(it, "ogg_320_hash")?.let { value -> getOrPut(AudioQuality.EXHIGH) { value } }
    }
}

/**
 * 回读“我喜欢”时每条收藏只取一个档位 hash，集合大小与原实现一致。
 * 依次尝试常见档位字段，避免只有 HQ／SQ 字段的记录被忽略；收藏项编号不参与判定。
 */
internal fun JSONObject.kugouFavoriteHash(): String? =
    listOf("FileHash", "HQFileHash", "SQFileHash", "hash", "Hash").firstNotNullOfOrNull { name ->
        optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

internal fun Int.kugouQualityFromLevel(): AudioQuality? = when (this) {
    2 -> AudioQuality.STANDARD
    4 -> AudioQuality.EXHIGH
    5 -> AudioQuality.LOSSLESS
    6, 7 -> AudioQuality.HI_RES
    else -> null
}
