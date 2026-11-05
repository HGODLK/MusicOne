package com.musicone

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** 有真实用量时按上一请求校准估算；字节数不再当作 token。 */
internal object AiContextBudget {
    fun estimate(text: String): Int {
        var ascii = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            if (point < 128) ascii++ else other++
            index += Character.charCount(point)
        }
        return (ascii + 3) / 4 + other
    }

    fun size(messages: JSONArray, tools: JSONArray): Int = estimate(messages.toString()) + estimate(tools.toString()) + messages.length() * 4

    fun predicted(checkpoint: JSONObject, configuration: AiConfiguration, estimate: Int): Long {
        val sample = checkpoint.optJSONObject("contextTokenSample") ?: return estimate.toLong()
        if (sample.optString("model") != configuration.modelId || sample.optString("provider") != configuration.provider.name || sample.optString("baseUrl") != configuration.baseUrl) return estimate.toLong()
        val prior = sample.optInt("estimate")
        val actual = sample.optLong("promptTokens")
        if (prior <= 0 || actual <= 0) return estimate.toLong()
        return ceil(estimate.toDouble() * actual / prior).toLong()
    }

    /** 将真实窗口换算为估算单位，供应商已拒绝时确保本轮确实释放空间。 */
    fun compactionCapacity(checkpoint: JSONObject, config: AiConfiguration, capacity: Int?, estimate: Int, rejected: Boolean = false): Int {
        val reserve = config.maxOutputTokens
        val predicted = predicted(checkpoint, config, estimate).coerceAtLeast(1)
        val calibrated = capacity?.let { ((it - reserve).toLong() * estimate / predicted + reserve).toInt() }
        val reduced = estimate - maxOf(reserve, estimate / 4) + reserve
        return if (rejected) minOf(calibrated ?: reduced, reduced) else calibrated ?: estimate
    }

    fun record(checkpoint: JSONObject, config: AiConfiguration, estimate: Int, usage: AiUsage) {
        if (usage.promptTokens > 0) checkpoint.put("contextTokenSample", JSONObject().put("provider", config.provider.name)
            .put("model", config.modelId).put("baseUrl", config.baseUrl).put("estimate", estimate).put("promptTokens", usage.promptTokens))
    }

    fun needsCompaction(capacity: Int?, predicted: Long, outputReserve: Int): Boolean =
        capacity != null && predicted + outputReserve >= capacity
}
