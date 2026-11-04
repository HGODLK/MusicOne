package com.musicone

internal open class PlatformApiException(
    message: String,
    val apiCode: Int? = null,
) : Exception(message)
