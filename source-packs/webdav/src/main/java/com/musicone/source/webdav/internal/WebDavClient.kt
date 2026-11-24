package com.musicone.source.webdav.internal

import com.musicone.source.sdk.SourceOperationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class ScannedAudioItem(
    val entry: WebDavEntry,
    val lyricEntry: WebDavEntry?,
    val coverEntry: WebDavEntry?,
)

internal data class ResolvedMediaUrl(
    val url: String,
    val headers: Map<String, String>,
    val contentType: String?,
    val supportsRange: Boolean,
)

/**
 * 基于 OkHttp 的 WebDAV 协议客户端。
 * 支持 PROPFIND（Depth 0/1）、Basic/Digest 自动质询、安全重定向、递归目录扫描与歌词/封面下载。
 */
internal class WebDavClient(
    private val authManager: WebDavAuthManager = WebDavAuthManager(),
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun clearAuth(serverKey: String) = authManager.clearServer(serverKey)

    /** 校验服务器连接与凭据：PROPFIND Depth: 0。 */
    suspend fun testConnection(server: WebDavServer) = withContext(Dispatchers.IO) {
        val url = WebDavPaths.buildRequestUrl(server, "", asDirectory = true)
        executeWithAuthAndRedirect(
            server = server,
            method = "PROPFIND",
            initialUrl = url,
            extraHeaders = mapOf("Depth" to "0"),
            bodyBytes = PROPFIND_BODY_BYTES,
            bodyContentType = XML_MEDIA_TYPE,
        ).use { response ->
            val code = response.code
            when {
                code == 207 || code in 200..299 -> Unit
                code == 401 -> throw SourceOperationException("AUTH_EXPIRED", "用户名或密码错误（HTTP 401）")
                code == 403 -> throw SourceOperationException("AUTH_EXPIRED", "没有访问该目录的权限（HTTP 403）")
                code == 404 -> throw SourceOperationException("SOURCE_REQUEST_FAILED", "路径不存在，请检查服务器地址或起始路径（HTTP 404）")
                code == 405 -> throw SourceOperationException("SOURCE_REQUEST_FAILED", "目标地址不支持 WebDAV（HTTP 405）")
                else -> throw SourceOperationException("SOURCE_REQUEST_FAILED", "服务器返回异常状态：HTTP $code", retryable = code >= 500)
            }
        }
    }

    /** 列出单个目录（PROPFIND Depth: 1），仅保留子文件夹、音频文件、歌词和封面。 */
    suspend fun listDirectory(server: WebDavServer, relativePath: String): WebDavDirectoryListing =
        withContext(Dispatchers.IO) {
            val normalized = WebDavPaths.normalizeRelativePath(relativePath)
            val url = WebDavPaths.buildRequestUrl(server, normalized, asDirectory = true)
            executeWithAuthAndRedirect(
                server = server,
                method = "PROPFIND",
                initialUrl = url,
                extraHeaders = mapOf("Depth" to "1"),
                bodyBytes = PROPFIND_BODY_BYTES,
                bodyContentType = XML_MEDIA_TYPE,
            ).use { response ->
                val code = response.code
                if (code == 401 || code == 403) {
                    throw SourceOperationException("AUTH_EXPIRED", "鉴权失败（HTTP $code），请检查账号密码")
                }
                if (code == 404 || code == 410) {
                    throw SourceOperationException("NOT_FOUND", "文件夹不存在或已被删除（HTTP $code）")
                }
                if (code != 207 && code !in 200..299) {
                    throw SourceOperationException("SOURCE_REQUEST_FAILED", "读取目录失败（HTTP $code）", retryable = code >= 500)
                }
                val xml = response.body?.string().orEmpty()
                if (xml.isBlank()) {
                    throw SourceOperationException("SOURCE_REQUEST_FAILED", "服务器未返回目录内容", retryable = true)
                }
                WebDavXmlParser.parseDirectoryListing(
                    xml = xml,
                    baseUrl = server.baseUrl,
                    rootPath = server.rootPath,
                    requestedRelativePath = normalized,
                )
            }
        }

    /**
     * 递归扫描指定目录中的全部音频文件。
     * 区分真实删除（NOT_FOUND）与暂时故障（抛出异常），绝不把访问失败伪装成空文件夹。
     */
    suspend fun scanAudio(
        server: WebDavServer,
        dirPath: String,
        includeSubfolders: Boolean,
        maxDepth: Int = 12,
        maxDirectories: Int = 400,
    ): List<ScannedAudioItem> {
        val result = mutableListOf<ScannedAudioItem>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val start = WebDavPaths.normalizeRelativePath(dirPath)
        queue.add(start to 0)
        visited += start

        while (queue.isNotEmpty() && visited.size <= maxDirectories) {
            coroutineContext.ensureActive()
            val (currentDir, depth) = queue.removeFirst()
            val listing = listDirectory(server, currentDir)
            for (audio in listing.audioFiles) {
                val stem = WebDavPaths.stemOf(audio.name).lowercase()
                result += ScannedAudioItem(
                    entry = audio,
                    lyricEntry = listing.lyricFilesByStem[stem],
                    coverEntry = listing.coverFile,
                )
            }
            if (includeSubfolders && depth < maxDepth) {
                for (sub in listing.directories) {
                    if (visited.add(sub.path)) {
                        queue.add(sub.path to (depth + 1))
                    }
                }
            }
        }
        return result
    }

    /** 读取同目录 `.lrc` 歌词文本，自动识别 UTF-8 / UTF-16 / GB18030。 */
    suspend fun fetchText(server: WebDavServer, relativePath: String, maxBytes: Int = 2 * 1024 * 1024): String? =
        withContext(Dispatchers.IO) {
            val url = WebDavPaths.buildRequestUrl(server, relativePath, asDirectory = false)
            executeWithAuthAndRedirect(server, "GET", url).use { response ->
                if (response.code == 404 || response.code == 410) return@withContext null
                if (response.code == 401 || response.code == 403) {
                    throw SourceOperationException("AUTH_EXPIRED", "读取歌词鉴权失败（HTTP ${response.code}）")
                }
                if (!response.isSuccessful) {
                    throw SourceOperationException("SOURCE_REQUEST_FAILED", "读取歌词失败（HTTP ${response.code}）", retryable = true)
                }
                val body = response.body ?: return@withContext null
                val bytes = readLimited(body.byteStream(), maxBytes)
                decodeSmartText(bytes)
            }
        }

    /** 下载图片文件到包私有持久化路径（原子写入）。 */
    suspend fun downloadToFile(server: WebDavServer, relativePath: String, target: File, maxBytes: Int = 10 * 1024 * 1024): Boolean =
        withContext(Dispatchers.IO) {
            if (target.isFile && target.length() > 0) return@withContext true
            val url = WebDavPaths.buildRequestUrl(server, relativePath, asDirectory = false)
            executeWithAuthAndRedirect(server, "GET", url).use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val bytes = readLimited(body.byteStream(), maxBytes)
                if (bytes.isEmpty()) return@withContext false
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
                true
            }
        }

    /**
     * 为播放器和 MediaMetadataRetriever 准备带有效鉴权头的真实请求地址。
     * 若服务器采用 Digest 或存在 302 跳转到直链，先探测一次以拿到最终地址、最新鉴权头及 Range 支持状态。
     */
    suspend fun resolveMediaUrl(server: WebDavServer, relativePath: String, probeNetwork: Boolean): ResolvedMediaUrl =
        withContext(Dispatchers.IO) {
            val initialUrl = WebDavPaths.buildRequestUrl(server, relativePath, asDirectory = false)
            val requestUri = extractRequestUri(initialUrl)

            if (!probeNetwork && authManager.currentScheme(server.key) != WebDavAuthScheme.DIGEST) {
                val headers = buildMap {
                    authManager.preemptiveHeader(server, "GET", requestUri)?.let { put("Authorization", it) }
                }
                return@withContext ResolvedMediaUrl(initialUrl, headers, null, true)
            }

            // 用带 Range: bytes=0-0 的 GET 探测（比 HEAD 更兼容网盘代理与 Digest），不拉整首音频
            var currentUrl = initialUrl
            var authHeader = authManager.preemptiveHeader(server, "GET", requestUri)
            var lastAuthForFinal = authHeader

            for (hop in 0 until MAX_REDIRECTS) {
                coroutineContext.ensureActive()
                val reqBuilder = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .header("Range", "bytes=0-0")
                authHeader?.let { reqBuilder.header("Authorization", it) }

                http.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code == 401 && authManager.hasCredentials(server)) {
                        val nextAuth = authManager.respondToChallenge(
                            server = server,
                            wwwAuthenticateHeaders = response.headers("WWW-Authenticate"),
                            method = "GET",
                            requestUri = extractRequestUri(currentUrl),
                        )
                        if (nextAuth != null && nextAuth != authHeader) {
                            authHeader = nextAuth
                            lastAuthForFinal = nextAuth
                            // 用新头重试当前跳
                            val retryReq = Request.Builder()
                                .url(currentUrl)
                                .get()
                                .header("Range", "bytes=0-0")
                                .header("Authorization", nextAuth)
                                .build()
                            http.newCall(retryReq).execute().use { retryResp ->
                                return@withContext handleProbeResult(server, currentUrl, retryResp, nextAuth)
                            }
                        }
                        throw SourceOperationException("AUTH_EXPIRED", "音频鉴权失败（HTTP 401）")
                    }
                    if (code in REDIRECT_CODES) {
                        val location = response.header("Location")
                            ?: throw SourceOperationException("SOURCE_REQUEST_FAILED", "重定向缺少目标地址")
                        val resolved = response.request.url.resolve(location)?.toString()
                            ?: throw SourceOperationException("SOURCE_REQUEST_FAILED", "重定向地址无效")
                        val sameOrigin = isSameOrigin(currentUrl, resolved)
                        currentUrl = resolved
                        // 跨域直链（如网盘签名直链）不应携带原 WebDAV 的 Authorization 头
                        authHeader = if (sameOrigin) authManager.preemptiveHeader(server, "GET", extractRequestUri(resolved)) else null
                        lastAuthForFinal = authHeader
                        continue
                    }
                    return@withContext handleProbeResult(server, currentUrl, response, lastAuthForFinal)
                }
            }
            throw SourceOperationException("SOURCE_REQUEST_FAILED", "音频地址重定向次数过多")
        }

    private fun handleProbeResult(
        server: WebDavServer,
        finalUrl: String,
        response: Response,
        authHeaderUsed: String?,
    ): ResolvedMediaUrl {
        val code = response.code
        if (code == 401 || code == 403) {
            throw SourceOperationException("AUTH_EXPIRED", "没有访问该音频的权限（HTTP $code）")
        }
        if (code == 404 || code == 410) {
            throw SourceOperationException("TRACK_UNAVAILABLE", "远端音频文件不存在或已被移除（HTTP $code）")
        }
        if (code !in 200..299 && code != 416) {
            throw SourceOperationException("SOURCE_REQUEST_FAILED", "获取音频失败（HTTP $code）", retryable = code >= 500)
        }
        val supportsRange = code == 206 || response.header("Accept-Ranges").equals("bytes", ignoreCase = true)
        val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
        // Digest 的 nonce 计数每次请求应递增，为播放器现算一次新鲜头
        val finalAuth = if (authHeaderUsed != null && authManager.currentScheme(server.key) == WebDavAuthScheme.DIGEST) {
            authManager.preemptiveHeader(server, "GET", extractRequestUri(finalUrl)) ?: authHeaderUsed
        } else {
            authHeaderUsed
        }
        val headers = buildMap {
            finalAuth?.let { put("Authorization", it) }
        }
        return ResolvedMediaUrl(finalUrl, headers, contentType, supportsRange)
    }

    /** 返回同步预认证头，供轻量本地探测调用。 */
    fun buildAuthHeadersForUrl(server: WebDavServer, url: String): Map<String, String> {
        val header = authManager.preemptiveHeader(server, "GET", extractRequestUri(url)) ?: return emptyMap()
        return mapOf("Authorization" to header)
    }

    private suspend fun executeWithAuthAndRedirect(
        server: WebDavServer,
        method: String,
        initialUrl: String,
        extraHeaders: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        bodyContentType: String? = null,
    ): Response {
        var currentUrl = initialUrl
        var authHeader = authManager.preemptiveHeader(server, method, extractRequestUri(currentUrl))
        var authRetried = false

        for (hop in 0 until MAX_REDIRECTS) {
            coroutineContext.ensureActive()
            val response = executeSingle(method, currentUrl, extraHeaders, authHeader, bodyBytes, bodyContentType)
            val code = response.code

            if (code == 401 && !authRetried && authManager.hasCredentials(server)) {
                val challenges = response.headers("WWW-Authenticate")
                response.close()
                val nextAuth = authManager.respondToChallenge(server, challenges, method, extractRequestUri(currentUrl))
                if (nextAuth != null) {
                    authHeader = nextAuth
                    authRetried = true
                    continue
                }
                throw SourceOperationException("AUTH_EXPIRED", "用户名或密码错误（HTTP 401）")
            }

            if (code in REDIRECT_CODES) {
                val location = response.header("Location")
                val resolved = location?.let { response.request.url.resolve(it)?.toString() }
                response.close()
                if (resolved == null) {
                    throw SourceOperationException("SOURCE_REQUEST_FAILED", "重定向目标地址无效")
                }
                val sameOrigin = isSameOrigin(currentUrl, resolved)
                currentUrl = resolved
                authHeader = if (sameOrigin) authManager.preemptiveHeader(server, method, extractRequestUri(resolved)) else null
                authRetried = false
                continue
            }

            return response
        }
        throw SourceOperationException("SOURCE_REQUEST_FAILED", "请求重定向次数过多")
    }

    private fun executeSingle(
        method: String,
        url: String,
        extraHeaders: Map<String, String>,
        authHeader: String?,
        bodyBytes: ByteArray?,
        bodyContentType: String?,
    ): Response {
        val builder = Request.Builder().url(url)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        authHeader?.let { builder.header("Authorization", it) }
        val body = bodyBytes?.toRequestBody(bodyContentType?.toMediaType())
        builder.method(method, body)
        return try {
            http.newCall(builder.build()).execute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            throw SourceOperationException("NETWORK_ERROR", "连接服务器失败：${io.message ?: "请检查网络或服务器地址"}", retryable = true)
        }
    }

    private fun extractRequestUri(url: String): String {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return "/"
        val path = parsed.rawPath?.ifEmpty { "/" } ?: "/"
        val query = parsed.rawQuery
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    private fun isSameOrigin(urlA: String, urlB: String): Boolean {
        val a = runCatching { java.net.URI(urlA) }.getOrNull() ?: return false
        val b = runCatching { java.net.URI(urlB) }.getOrNull() ?: return false
        val portA = if (a.port != -1) a.port else if (a.scheme.equals("https", true)) 443 else 80
        val portB = if (b.port != -1) b.port else if (b.scheme.equals("https", true)) 443 else 80
        return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && portA == portB
    }

    private fun readLimited(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun decodeSmartText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { return utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        val gbk = runCatching { Charset.forName("GB18030") }.getOrElse { Charsets.UTF_8 }
        return String(bytes, gbk)
    }

    companion object {
        private const val MAX_REDIRECTS = 6
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val XML_MEDIA_TYPE = "application/xml; charset=utf-8"
        private val PROPFIND_BODY_BYTES = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:displayname/>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getetag/>
                <D:getlastmodified/>
                <D:getcontenttype/>
              </D:prop>
            </D:propfind>
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
