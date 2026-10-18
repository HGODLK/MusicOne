package com.musicone.demo

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B站网络请求签名工具（包含 Web Wbi 签名与 App 客户端签名）。
 */
internal object BiliSigner {
    // 扫码授权与 App 客户端公开协议标识（非机密）
    private const val APP_KEY = "dfca71928277209b"
    private const val APP_SECRET = "b5475a8825547a4fc26c7d518eaaa02e"

    // WBI mixin key 固定置换表
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    /**
     * 根据 img_key 与 sub_key 计算 WBI mixin key。
     */
    fun getMixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        require(raw.length >= 64) { "WBI 签名材料不完整" }
        return MIXIN_KEY_ENC_TAB.take(32).map { raw[it] }.joinToString("")
    }

    /**
     * WBI 参数签名，自动附加秒级时间戳 wts 与摘要 w_rid。
     */
    fun signWbi(params: Map<String, String>, mixinKey: String): Map<String, String> {
        val withTs = params.toMutableMap()
        withTs["wts"] = (System.currentTimeMillis() / 1000L).toString()
        val sorted = withTs.mapValues { (_, value) -> value.filterNot { it in "!'()*" } }.toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return sorted + ("w_rid" to md5(query + mixinKey))
    }

    /**
     * 为 App 端请求计算带 appkey 与 sign 的参数字典。
     */
    fun signApp(params: Map<String, String>): LinkedHashMap<String, String> {
        val complete = (params + mapOf("appkey" to APP_KEY, "ts" to (System.currentTimeMillis() / 1000L).toString())).toSortedMap()
        val query = complete.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val signature = md5(query + APP_SECRET)
        return linkedMapOf<String, String>().apply {
            putAll(complete)
            put("sign", signature)
        }
    }

    /**
     * 计算输入字符串的 MD5 十六进制小写值。
     */
    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * URL 编码，严格将空格转为 %20。
     */
    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
