package com.musicone

import com.musicone.source.sdk.*
import java.net.URLEncoder

private const val PUBLIC_SUBSCRIPTION_PREFIX = "@musicone-public/v1/"

/** 公开订阅使用独立存储命名空间，不创建账号，也不能与真实账号合并。 */
internal fun supplementalSubscriptionAccountId(entry: SourceEntry, account: SourceAccount?): String? {
    val provider = entry.supplementalPageProviderOrNull() ?: return account?.id
    account?.id?.let {
        require(!it.startsWith(PUBLIC_SUBSCRIPTION_PREFIX)) { "真实账号使用了公开订阅保留命名空间" }
        return it
    }
    val identity = provider.publicSubscriptionIdentity()?.takeIf { it.isNotBlank() } ?: return null
    return PUBLIC_SUBSCRIPTION_PREFIX + URLEncoder.encode(identity, "UTF-8")
}
