package com.musicone.source.qq.internal

internal fun String.upgradePlaybackUrlToHttps(): String {
    val normalized = trim().replace("\\/", "/")
    return if (normalized.startsWith("http://", ignoreCase = true)) {
        "https://${normalized.substring(7)}"
    } else {
        normalized
    }
}

