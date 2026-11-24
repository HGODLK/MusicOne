package com.musicone.source.webdav.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal enum class WebDavAuthScheme { NONE, BASIC, DIGEST }

internal data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val qop: String?,
    val opaque: String?,
    val algorithm: String,
    val stale: Boolean,
)

/**
 * HTTP Basic 与 Digest（RFC 2617 / RFC 7616）鉴权工具。
 * 按服务器维度缓存已协商的鉴权方式和 Digest challenge，避免每次请求多一次 401 往返。
 */
internal class WebDavAuthManager {
    private val schemes = ConcurrentHashMap<String, WebDavAuthScheme>()
    private val challenges = ConcurrentHashMap<String, DigestChallenge>()
    private val nonceCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val random = SecureRandom()

    fun hasCredentials(server: WebDavServer): Boolean =
        server.username.isNotEmpty() || server.password.isNotEmpty()

    /** 根据已缓存协商结果构造预认证头；未协商且提供凭据时先不盲发或优先 Basic。 */
    fun preemptiveHeader(server: WebDavServer, method: String, requestUri: String): String? {
        if (!hasCredentials(server)) return null
        return when (schemes[server.key]) {
            WebDavAuthScheme.DIGEST -> {
                val challenge = challenges[server.key] ?: return null
                buildDigestHeader(server.username, server.password, method, requestUri, challenge)
            }
            WebDavAuthScheme.BASIC -> buildBasicHeader(server.username, server.password)
            WebDavAuthScheme.NONE -> null
            null -> buildBasicHeader(server.username, server.password)
        }
    }

    /** 收到 401 响应后解析 WWW-Authenticate 头部并生成对应 Authorization 头。 */
    fun respondToChallenge(
        server: WebDavServer,
        wwwAuthenticateHeaders: List<String>,
        method: String,
        requestUri: String,
    ): String? {
        if (!hasCredentials(server)) return null
        val digestRaw = findChallengeHeader(wwwAuthenticateHeaders, "Digest")
        if (digestRaw != null) {
            val challenge = parseDigestChallenge(digestRaw) ?: return null
            schemes[server.key] = WebDavAuthScheme.DIGEST
            challenges[server.key] = challenge
            nonceCounters[server.key] = AtomicInteger(0)
            return buildDigestHeader(server.username, server.password, method, requestUri, challenge)
        }
        val basicRaw = findChallengeHeader(wwwAuthenticateHeaders, "Basic")
        if (basicRaw != null) {
            schemes[server.key] = WebDavAuthScheme.BASIC
            return buildBasicHeader(server.username, server.password)
        }
        return null
    }

    fun clearServer(serverKey: String) {
        schemes.remove(serverKey)
        challenges.remove(serverKey)
        nonceCounters.remove(serverKey)
    }

    fun currentScheme(serverKey: String): WebDavAuthScheme? = schemes[serverKey]

    private fun buildDigestHeader(
        username: String,
        password: String,
        method: String,
        requestUri: String,
        challenge: DigestChallenge,
    ): String {
        val counter = nonceCounters.computeIfAbsent(challenge.nonce) { AtomicInteger(0) }.incrementAndGet()
        val nc = String.format(Locale.ROOT, "%08x", counter)
        val cnonceBytes = ByteArray(8).also { random.nextBytes(it) }
        val cnonce = toHex(cnonceBytes)
        return computeDigestAuthorization(
            username = username,
            password = password,
            method = method,
            uri = requestUri,
            challenge = challenge,
            nc = nc,
            cnonce = cnonce,
        )
    }

    companion object {
        fun buildBasicHeader(username: String, password: String): String {
            val raw = "$username:$password".toByteArray(Charsets.UTF_8)
            val encoded = Base64.getEncoder().encodeToString(raw)
            return "Basic $encoded"
        }

        fun findChallengeHeader(headers: List<String>, scheme: String): String? {
            for (header in headers) {
                val trimmed = header.trim()
                if (trimmed.startsWith("$scheme ", ignoreCase = true)) return trimmed
                // 某些服务器会在单个头里逗号拼接多个质询，如 Basic realm="...", Digest realm="..."
                val regex = Regex("(?i)(?:^|,\\s*)($scheme\\s+.+)$")
                val match = regex.find(trimmed)
                if (match != null) return match.groupValues[1].trim()
            }
            return null
        }

        fun parseDigestChallenge(header: String): DigestChallenge? {
            val body = header.trim().removePrefix("Digest").removePrefix("digest").trim()
            val params = parseAuthParams(body)
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qopRaw = params["qop"]
            val qop = qopRaw?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }
                ?.firstOrNull { it == "auth" }
            val algorithm = params["algorithm"]?.trim() ?: "MD5"
            val opaque = params["opaque"]
            val stale = params["stale"].equals("true", ignoreCase = true)
            return DigestChallenge(realm, nonce, qop, opaque, algorithm, stale)
        }

        fun computeDigestAuthorization(
            username: String,
            password: String,
            method: String,
            uri: String,
            challenge: DigestChallenge,
            nc: String,
            cnonce: String,
        ): String {
            val algoUpper = challenge.algorithm.uppercase(Locale.ROOT)
            val hashName = when {
                algoUpper.startsWith("SHA-256") -> "SHA-256"
                algoUpper.startsWith("SHA-512") -> "SHA-512"
                else -> "MD5"
            }
            val isSess = algoUpper.endsWith("-SESS")
            var ha1 = hashHex(hashName, "$username:${challenge.realm}:$password")
            if (isSess) {
                ha1 = hashHex(hashName, "$ha1:${challenge.nonce}:$cnonce")
            }
            val ha2 = hashHex(hashName, "$method:$uri")
            val response = if (challenge.qop != null) {
                hashHex(hashName, "$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
            } else {
                hashHex(hashName, "$ha1:${challenge.nonce}:$ha2")
            }
            val parts = mutableListOf(
                "username=\"${escapeQuoted(username)}\"",
                "realm=\"${escapeQuoted(challenge.realm)}\"",
                "nonce=\"${escapeQuoted(challenge.nonce)}\"",
                "uri=\"${escapeQuoted(uri)}\"",
                "response=\"$response\"",
            )
            if (challenge.algorithm.isNotBlank()) {
                parts += "algorithm=${challenge.algorithm}"
            }
            if (challenge.opaque != null) {
                parts += "opaque=\"${escapeQuoted(challenge.opaque)}\""
            }
            if (challenge.qop != null) {
                parts += "qop=${challenge.qop}"
                parts += "nc=$nc"
                parts += "cnonce=\"$cnonce\""
            }
            return "Digest " + parts.joinToString(", ")
        }

        internal fun parseAuthParams(input: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            var i = 0
            val len = input.length
            while (i < len) {
                while (i < len && (input[i] == ' ' || input[i] == ',' || input[i] == '\t')) i++
                val keyStart = i
                while (i < len && input[i] != '=' && input[i] != ',') i++
                if (i >= len || input[i] != '=') break
                val key = input.substring(keyStart, i).trim().lowercase(Locale.ROOT)
                i++ // 跳过 '='
                while (i < len && input[i] == ' ') i++
                val value = if (i < len && input[i] == '"') {
                    i++ // 跳过起始引号
                    val sb = StringBuilder()
                    while (i < len && input[i] != '"') {
                        if (input[i] == '\\' && i + 1 < len) {
                            i++
                        }
                        sb.append(input[i])
                        i++
                    }
                    if (i < len && input[i] == '"') i++
                    sb.toString()
                } else {
                    val valStart = i
                    while (i < len && input[i] != ',') i++
                    input.substring(valStart, i).trim()
                }
                if (key.isNotEmpty()) result[key] = value
            }
            return result
        }

        private fun escapeQuoted(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun hashHex(algorithm: String, input: String): String {
            val bytes = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
            return toHex(bytes)
        }

        private fun toHex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4])
                out.append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private val HEX = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )
    }
}
