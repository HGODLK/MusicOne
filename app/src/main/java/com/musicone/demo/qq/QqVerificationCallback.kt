package com.musicone.demo

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** 识别官方验证页的关闭回调，关闭后由服务器重试原请求确认结果。 */
internal fun qqVerificationPage(url: String): Boolean {
    val uri = runCatching { URI.create(url) }.getOrNull() ?: return false
    val host = uri.host.orEmpty().lowercase()
    return uri.scheme.equals("https", ignoreCase = true) && (host == "qq.com" || host.endsWith(".qq.com") ||
        host == "qcloud.com" || host.endsWith(".qcloud.com"))
}

internal fun qqVerificationCloseCallback(url: String): Boolean {
    val uri = runCatching { URI.create(url) }.getOrNull() ?: return false
    return uri.scheme.equals("qqmusic", ignoreCase = true) && uri.host.equals("qq.com", ignoreCase = true) &&
        uri.path.orEmpty().substringAfterLast('/') in setOf("closeWebView", "closeWebview", "finishWebView")
}

internal fun qqVkeyVerificationPage(url: String): Boolean {
    if (!qqVerificationPage(url)) return false
    val query = runCatching { URI.create(url).rawQuery.orEmpty() }.getOrDefault("")
    return query.split('&').any { part ->
        part.substringBefore('=').equals("_vkey_verify", ignoreCase = true) &&
            part.substringAfter('=', "") == "1"
    }
}

/** 把服务端原始验证地址交给官方 QQ 音乐，保留其登录态和设备桥接环境。 */
internal fun qqOfficialVerificationDeepLink(url: String): String? {
    if (!qqVkeyVerificationPage(url)) return null
    val payload = JSONObject().put("url", url).toString()
    return "qqmusic://qq.com/ui/openUrl?p=" +
        URLEncoder.encode(payload, StandardCharsets.UTF_8.toString())
}

/** 短链跳转到 y.qq.com 后仍需要同一组 QQ 凭证加载安全组件。 */
internal fun qqVerificationCookieTargets(url: String): List<String> {
    val uri = runCatching { URI.create(url) }.getOrNull() ?: return listOf(url)
    val host = uri.host.orEmpty().lowercase()
    if (host != "qq.com" && !host.endsWith(".qq.com")) return listOf(url)
    return linkedSetOf(url, "https://c.y.qq.com/", "https://y.qq.com/").toList()
}
