package com.musicone.source.kugou

internal open class PlatformApiException(
    message: String,
    val apiCode: Int? = null,
) : Exception(message)

