package com.musicone.sourceintegration

import org.json.JSONObject

/** 宿主维护的曲目载荷键，只保存在宿主资料中，不随 opaqueData 下发给音源。 */
internal val HOST_SOURCE_PAYLOAD_KEYS = setOf("sourcePayloadFormat", "sourcePayloadVersion", "sourcePayload")

/**
 * 修复旧版回传形成的载荷嵌套：音源把宿主键编码进自有载荷后，每次往返体积翻倍。
 * 去掉载荷里夹带的宿主键即可丢弃整条嵌套链，音源自有字段保持不变。
 */
internal fun MutableMap<String, String>.stripNestedHostPayload() {
    val encoded = get("sourcePayload") ?: return
    if (HOST_SOURCE_PAYLOAD_KEYS.none { encoded.contains("\"$it\"") }) return
    val value = runCatching { JSONObject(encoded) }.getOrNull() ?: return
    HOST_SOURCE_PAYLOAD_KEYS.forEach(value::remove)
    put("sourcePayload", value.toString())
}
