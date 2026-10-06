package com.musicone.demo

import javax.net.ssl.SSLPeerUnverifiedException

internal fun Throwable.asUserMessage(): String {
    val chain = generateSequence(this) { it.cause }
    if (chain.any { error ->
            error is SSLPeerUnverifiedException ||
                error.message.orEmpty().contains("subjectAltNames", ignoreCase = true) ||
                error.message.orEmpty().contains("wrong principal", ignoreCase = true)
        }
    ) {
        return "平台接口证书与域名不匹配，请稍后重试"
    }
    return message?.substringAfterLast(": ")?.takeIf(String::isNotBlank) ?: "请求失败，请稍后重试"
}
