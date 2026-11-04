package com.musicone.source.bilibili

internal open class PlatformApiException(
    message: String,
    val apiCode: Int? = null,
) : Exception(message)

