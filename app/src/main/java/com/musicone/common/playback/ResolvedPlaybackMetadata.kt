package com.musicone

/** 直接读取音源包解析出的真实音质，不再靠猜 URL 文件名。 */
internal fun resolvedPlaybackQuality(url: String): AudioQuality? =
    SupplementalPlaybackRequests.resource(url)?.actualQuality

internal fun resolvedPlaybackRequiresDolby(url: String): Boolean =
    SupplementalPlaybackRequests.resource(url)?.requiresDolbyCapability() == true
