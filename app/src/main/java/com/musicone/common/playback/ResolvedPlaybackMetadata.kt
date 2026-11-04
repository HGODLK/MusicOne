package com.musicone

/** 规格由包的解析结果提供，宿主不再根据平台 URL 命名猜测音质。 */
internal fun resolvedPlaybackQuality(url: String): AudioQuality? =
    SupplementalPlaybackRequests.resource(url)?.actualQuality

internal fun resolvedPlaybackRequiresDolby(url: String): Boolean =
    SupplementalPlaybackRequests.resource(url)?.requiresDolbyCapability() == true
