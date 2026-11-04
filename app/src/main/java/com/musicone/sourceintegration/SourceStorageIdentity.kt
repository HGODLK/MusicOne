package com.musicone.sourceintegration

import java.security.MessageDigest

// 原有四平台身份用于兼容 0.2.0；其他音源的局部 ID 由宿主隔离，包版本不参与身份。
private val legacyStorageSources = setOf("local", "com.musicone.source.qq", "com.musicone.source.kugou",
    "com.musicone.source.netease", "com.musicone.source.bilibili")
internal const val SOURCE_ORIGINAL_STABLE_ID = "musicone.host.originalStableId"

internal fun sourceStorageId(sourceId: String, accountId: String?, stableId: String): String {
    if (sourceId in legacyStorageSources) return stableId
    val account = accountId.orEmpty()
    val input = "${account.length}:$account:${stableId.length}:$stableId"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return "$sourceId:$digest"
}
