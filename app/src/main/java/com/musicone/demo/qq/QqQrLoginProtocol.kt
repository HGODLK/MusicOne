package com.musicone.demo

import java.net.URI
import java.net.URLDecoder

internal fun qqQrToken(value: String, seed: Int = 0): Int =
    value.fold(seed) { hash, char -> hash * 33 + char.code } and Int.MAX_VALUE

/** 回调只解析字符串和状态，不执行服务端返回的脚本。 */
internal data class QqQrCallback(val code: Int, val redirect: String)

internal fun parseQqQrCallback(text: String): QqQrCallback {
    val callback = Regex("ptuiCB\\((.*?)\\)\\s*;?", RegexOption.DOT_MATCHES_ALL).find(text)
        ?: throw PlatformApiException("QQ 扫码状态响应异常，请刷新二维码")
    val args = Regex("'((?:\\\\.|[^'\\\\])*)'").findAll(callback.groupValues[1])
        .map { it.groupValues[1].replace("\\'", "'").replace("\\/", "/").replace("\\\\", "\\") }.toList()
    val code = args.firstOrNull()?.toIntOrNull() ?: throw PlatformApiException("QQ 扫码状态响应异常")
    return QqQrCallback(code, args.getOrNull(2).orEmpty())
}

internal fun qqQrQuery(url: String): Map<String, String> = URI(url).rawQuery.orEmpty().split('&').mapNotNull {
    val pair = it.split('=', limit = 2)
    if (pair.size != 2) null else URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
}.toMap()

internal fun qqQrAuthorizationCode(location: String, expectedState: String = ""): String {
    val uri = runCatching { URI(location) }.getOrNull()
    if (uri?.scheme != "https" || uri.host != "y.qq.com" || uri.path != "/portal/wx_redirect.html" ||
        uri.userInfo != null || uri.port !in listOf(-1, 443)) {
        throw PlatformApiException("QQ 授权跳转异常，请刷新二维码")
    }
    val query = qqQrQuery(location)
    val state = query["state"].orEmpty()
    if (expectedState.isNotBlank() && state.isNotBlank() && state != expectedState) {
        throw PlatformApiException("QQ 授权状态校验失败，请刷新二维码")
    }
    return query["code"]?.takeIf(String::isNotBlank)
        ?: throw PlatformApiException("QQ 授权未返回登录凭据，请刷新二维码")
}

internal fun qqQrImage(bytes: ByteArray): Boolean =
    bytes.size >= 8 && (bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) ||
        bytes.take(3) == listOf(255, 216, 255).map(Int::toByte) || bytes.take(3) == "GIF".toByteArray().toList())

internal fun qqQrStatus(code: Int, wechat: Boolean): PlatformQrLoginStatus = when (code) {
    if (wechat) 408 else 66 -> PlatformQrLoginStatus.WAITING_SCAN
    if (wechat) 404 else 67 -> PlatformQrLoginStatus.WAITING_CONFIRM
    if (wechat) 402 else 65 -> PlatformQrLoginStatus.EXPIRED
    else -> PlatformQrLoginStatus.FAILED
}

internal fun qqQrStatusMessage(status: PlatformQrLoginStatus, scanApp: String): String = when (status) {
    PlatformQrLoginStatus.WAITING_SCAN -> "请使用${scanApp}扫码"
    PlatformQrLoginStatus.WAITING_CONFIRM -> "已扫码，请在${scanApp}中确认登录"
    PlatformQrLoginStatus.EXPIRED -> "二维码已过期，请重新生成"
    else -> "扫码登录已取消或失败，请重新生成二维码"
}
