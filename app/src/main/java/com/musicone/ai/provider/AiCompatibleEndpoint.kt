package com.musicone

import java.net.URI

/** DeepSeek 的搜索和聊天固定走官方地址，不受其他服务自定义链接影响。 */
internal fun aiProviderEndpoint(provider: AiProvider, customBaseUrl: String): String =
    if (provider == AiProvider.DEEPSEEK) provider.baseUrl else aiNormalizeBaseUrl(customBaseUrl)

/** 保留用户填的自定义路径前缀，调用方只往后拼标准接口路径。 */
internal fun aiNormalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val uri = runCatching { URI(trimmed) }.getOrNull()
    require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "请填写有效的 HTTP/HTTPS API 基址，不包含密钥、查询参数或片段"
    }
    return trimmed
}

/** 把旧版 MiMo 配置迁到“其他服务”，密文直接复用原 Keystore，不解密也不覆盖已有配置。 */
internal fun aiMigrateProviderSettings(old: Map<String, *>): Map<String, Any> {
    if (old["mimo_migrated_to_other"] == true || (old["provider"] != "MIMO" && old.keys.none { it.endsWith("_MIMO") })) return emptyMap()
    return buildMap {
        val hasOther = old.containsKey("key_OTHER")
        listOf("key", "models", "model", "reselect").forEach { prefix ->
            val key = "${prefix}_OTHER"
            if (!old.containsKey(key)) old["${prefix}_MIMO"]?.let { put(key, it) }
        }
        if (!hasOther) {
            put("base_url_OTHER", "https://api.xiaomimimo.com/v1")
            put("verified_OTHER", false)
        }
        if (old["provider"] == "MIMO") put("provider", "OTHER")
        put("mimo_migrated_to_other", true)
    }
}
