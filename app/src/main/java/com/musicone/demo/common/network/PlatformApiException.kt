package com.musicone.demo

internal open class PlatformApiException(
    message: String,
    val apiCode: Int? = null,
) : Exception(message)
