package com.musicone.source.qq.internal

internal open class PlatformApiException(
    message: String,
    val apiCode: Int? = null,
) : Exception(message)
