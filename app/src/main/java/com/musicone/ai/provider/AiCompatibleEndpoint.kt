package com.musicone

import java.net.URI

/** DeepSeek 的搜索和聊天固定走官方地址，不受其他服务自定义链接影响。 */
internal fun aiProviderEndpoint(provider: AiProvider, customBaseUrl: String): String =
    if (provider == AiProvider.DEEPSEEK) provider.baseUrl else aiNormalizeBaseUrl(customBaseUrl)

/** 基址保留服务自定义路径，调用方只追加标准接口路径。 */
internal fun aiNormalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val uri = runCatching { URI(trimmed) }.getOrNull()
    require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "请填写有效的 HTTP/HTTPS API 基址，不包含密钥、查询参数或片段"
    }
    return trimmed
}

/** 只复制旧配置；密钥密文沿用原 Keystore，不解密、打印或覆盖已有其他服务。 */
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
