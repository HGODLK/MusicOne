package com.musicone.source.subsonic.internal

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * 一台已登录的服务器。默认只保存 token 与 salt，不保存明文密码；
 * 仅当服务器不支持 token 认证时才保存十六进制编码的密码。
 */
internal data class SubsonicServer(
    val key: String,
    val baseUrl: String,
    val username: String,
    val salt: String,
    val token: String,
    val legacyPasswordHex: String? = null,
    val serverType: String = "",
    val serverVersion: String = "",
    val openSubsonic: Boolean = false,
    val extensions: Set<String> = emptySet(),
    val expired: Boolean = false,
    val addedAtMs: Long = 0,
) {
    val displayHost: String get() = baseUrl.substringAfter("://")
    val displayName: String get() = "$username@$displayHost"
    val supportsStructuredLyrics: Boolean get() = "songLyrics" in extensions

    /** 每个请求都要附带的认证与协议参数。 */
    fun authParameters(): List<Pair<String, String>> = buildList {
        add("u" to username)
        if (legacyPasswordHex != null) add("p" to "enc:$legacyPasswordHex")
        else { add("t" to token); add("s" to salt) }
        add("v" to SUBSONIC_PROTOCOL_VERSION)
        add("c" to SUBSONIC_CLIENT_NAME)
        add("f" to "json")
    }

    override fun toString(): String = "SubsonicServer(key=$key, host=$displayHost, user=$username, credential=<已隐藏>)"
}

internal const val SUBSONIC_PROTOCOL_VERSION = "1.16.1"
internal const val SUBSONIC_CLIENT_NAME = "MusicOne"

/** 统一服务器地址：补全协议、去掉结尾的斜杠和 /rest，保留反向代理的子路径。 */
internal fun normalizeSubsonicUrl(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "请输入服务器地址" }
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val uri = try { URI(withScheme) } catch (_: Exception) { throw IllegalArgumentException("服务器地址格式不正确") }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "服务器地址只支持 http 或 https" }
    val host = uri.host
    require(!host.isNullOrBlank()) { "服务器地址缺少主机名" }
    val port = if (uri.port > 0) ":${uri.port}" else ""
    val hostText = if (':' in host && !host.startsWith("[")) "[$host]" else host.lowercase(Locale.ROOT)
    var path = uri.rawPath.orEmpty().trimEnd('/')
    if (path.endsWith("/rest", ignoreCase = true)) path = path.dropLast(5).trimEnd('/')
    return "$scheme://$hostText$port$path"
}

/** 服务器身份只由地址和用户名决定，换密码不会改变已保存的歌曲身份。 */
internal fun subsonicServerKey(baseUrl: String, username: String): String =
    sha256Hex("${baseUrl.lowercase(Locale.ROOT)}\n$username").take(16)

internal fun subsonicSalt(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(8).also(random::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

internal fun subsonicToken(password: String, salt: String): String = md5Hex(password + salt)

internal fun hexEncode(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
