package com.musicone.source.webdav.internal

import com.musicone.source.sdk.SourceArtwork
import com.musicone.source.sdk.SourcePayload
import com.musicone.source.sdk.SourceTrackDetails
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

internal const val WEBDAV_SOURCE_ID = "com.musicone.source.webdav"
internal const val WEBDAV_SOURCE_NAME = "WebDAV"
internal const val WEBDAV_VERSION_CODE = 1L
internal const val WEBDAV_VERSION_NAME = "0.1"
internal const val WEBDAV_MIN_HOST_VERSION_CODE = 6
internal const val WEBDAV_MIN_HOST_VERSION_NAME = "0.4.0-鲜花饼"
internal const val WEBDAV_PAGE_COLOR_ARGB = 0xFF2B6CB0L

internal const val WEBDAV_TRACK_PAYLOAD_FORMAT = "musicone.webdav.track"
internal const val WEBDAV_COLLECTION_PAYLOAD_FORMAT = "musicone.webdav.bookmark"

/** 常见音频扩展名集合（不含点号、小写）。 */
internal val WEBDAV_AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus",
    "wav", "ape", "wv", "dsf", "dff", "aiff", "aif",
    "alac", "wma", "mka", "mp2", "amr",
)

/** 同目录封面候选文件名优先级（不含扩展名、小写）。 */
internal val WEBDAV_COVER_BASENAMES: List<String> = listOf(
    "cover", "folder", "front", "album", "artwork", "thumb",
)

internal val WEBDAV_IMAGE_EXTENSIONS: Set<String> = setOf(
    "jpg", "jpeg", "png", "webp",
)

internal data class WebDavServer(
    val key: String,
    val displayName: String,
    val baseUrl: String,
    val rootPath: String,
    val username: String,
    val password: String,
    val expired: Boolean = false,
) {
    /** 供 SDK 使用的稳定账号 ID：服务器地址 + 用户名哈希。 */
    val accountId: String get() = key

    /** 用于 UI 展示的地址摘要，不包含凭据。 */
    val subtitleUrl: String
        get() = if (rootPath.isEmpty()) baseUrl else "$baseUrl$rootPath"
}

internal data class WebDavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L,
    val etag: String = "",
    val lastModified: String = "",
    val contentType: String = "",
)

internal data class WebDavDirectoryListing(
    val path: String,
    val directories: List<WebDavEntry>,
    val audioFiles: List<WebDavEntry>,
    val lyricFilesByStem: Map<String, WebDavEntry>,
    val coverFile: WebDavEntry?,
)

internal data class WebDavTrackMeta(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkFileUri: String? = null,
    val detectedCodecMime: String? = null,
    val etag: String = "",
    val sizeBytes: Long = -1L,
    val lastModified: String = "",
)

internal data class WebDavBookmark(
    val id: String,
    val serverKey: String,
    val path: String,
    val title: String,
    val includeSubfolders: Boolean = true,
    val trackCount: Int? = null,
    val coverFileUri: String? = null,
)

internal data class WebDavTrackPayload(
    val serverKey: String,
    val path: String,
    val fileName: String,
    val etag: String = "",
    val sizeBytes: Long = -1L,
    val lastModified: String = "",
    val contentType: String = "",
    val lyricPath: String? = null,
    val coverPath: String? = null,
    val detectedCodecMime: String? = null,
) {
    fun toSourcePayload(): SourcePayload {
        val json = JSONObject().apply {
            put("serverKey", serverKey)
            put("path", path)
            put("fileName", fileName)
            if (etag.isNotEmpty()) put("etag", etag)
            if (sizeBytes >= 0) put("sizeBytes", sizeBytes)
            if (lastModified.isNotEmpty()) put("lastModified", lastModified)
            if (contentType.isNotEmpty()) put("contentType", contentType)
            if (!lyricPath.isNullOrEmpty()) put("lyricPath", lyricPath)
            if (!coverPath.isNullOrEmpty()) put("coverPath", coverPath)
            if (!detectedCodecMime.isNullOrEmpty()) put("detectedCodecMime", detectedCodecMime)
        }
        return SourcePayload(WEBDAV_TRACK_PAYLOAD_FORMAT, 1, json.toString())
    }

    companion object {
        fun parse(payload: SourcePayload): WebDavTrackPayload? {
            if (payload.format != WEBDAV_TRACK_PAYLOAD_FORMAT || payload.encoded.isBlank()) return null
            return runCatching {
                val json = JSONObject(payload.encoded)
                val serverKey = json.optString("serverKey")
                val path = json.optString("path")
                if (serverKey.isBlank() || path.isBlank()) return null
                WebDavTrackPayload(
                    serverKey = serverKey,
                    path = normalizeRelativePath(path),
                    fileName = json.optString("fileName").ifBlank { fileNameOf(path) },
                    etag = json.optString("etag"),
                    sizeBytes = json.optLong("sizeBytes", -1L),
                    lastModified = json.optString("lastModified"),
                    contentType = json.optString("contentType"),
                    lyricPath = json.optString("lyricPath").takeIf { it.isNotBlank() },
                    coverPath = json.optString("coverPath").takeIf { it.isNotBlank() },
                    detectedCodecMime = json.optString("detectedCodecMime").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }
}

internal object WebDavPaths {
    /** 规范化用户输入的 WebDAV 服务器基础地址（保留协议、主机、端口及根前缀路径，移除尾斜杠与查询串）。 */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "请输入服务器地址" }
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrElse {
            throw IllegalArgumentException("服务器地址格式不正确")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "仅支持 http 或 https 地址" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("服务器地址缺少主机名")
        val authority = buildString {
            append(host)
            if (uri.port != -1) {
                val isDefault = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
                if (!isDefault) append(':').append(uri.port)
            }
        }
        val rawPath = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$authority$rawPath"
    }

    /** 规范化起始子路径或相对文件路径：空或 "/" 表示根，非根以 "/" 开头且不带尾 "/"。 */
    fun normalizeRelativePath(raw: String): String {
        val cleaned = raw.trim().replace('\\', '/')
        if (cleaned.isEmpty() || cleaned == "/") return ""
        val segments = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        val resolved = ArrayList<String>(segments.size)
        for (segment in segments) {
            if (segment == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            } else {
                resolved.add(segment)
            }
        }
        return if (resolved.isEmpty()) "" else "/" + resolved.joinToString("/")
    }

    fun joinPath(parent: String, childName: String): String {
        val p = normalizeRelativePath(parent)
        val c = childName.trim().trim('/')
        return when {
            c.isEmpty() -> p
            p.isEmpty() -> "/$c"
            else -> "$p/$c"
        }
    }

    fun parentPath(path: String): String {
        val normalized = normalizeRelativePath(path)
        if (normalized.isEmpty()) return ""
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "" else normalized.substring(0, idx)
    }

    fun fileNameOf(path: String): String {
        val normalized = normalizeRelativePath(path)
        if (normalized.isEmpty()) return ""
        val idx = normalized.lastIndexOf('/')
        return if (idx < 0) normalized else normalized.substring(idx + 1)
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    fun isAudioFile(name: String): Boolean = extensionOf(name) in WEBDAV_AUDIO_EXTENSIONS

    fun isLyricFile(name: String): Boolean = extensionOf(name) == "lrc"

    fun isImageFile(name: String): Boolean = extensionOf(name) in WEBDAV_IMAGE_EXTENSIONS

    /** 按 RFC 3986 逐段编码路径，空格编为 %20，保留斜杠。 */
    fun encodePathSegments(path: String): String {
        val normalized = normalizeRelativePath(path)
        if (normalized.isEmpty()) return ""
        return normalized.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else encodeSegment(segment)
        }
    }

    private fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val unreserved = (c in 'a'.code..'z'.code) ||
                (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX_CHARS[(c ushr 4) and 0x0F])
                out.append(HEX_CHARS[c and 0x0F])
            }
        }
        return out.toString()
    }

    /** 将 URL 中的百分号编码安全解码为 UTF-8，不把 '+' 误转为空格。 */
    fun decodePercentPath(raw: String): String {
        val safePlus = raw.replace("+", "%2B")
        return runCatching { URLDecoder.decode(safePlus, "UTF-8") }.getOrDefault(raw)
    }

    /** 构造完整请求 URL：baseUrl + rootPath + relativePath（目录可按需补尾斜杠）。 */
    fun buildRequestUrl(server: WebDavServer, relativePath: String, asDirectory: Boolean = false): String {
        val fullRel = when {
            server.rootPath.isEmpty() -> normalizeRelativePath(relativePath)
            relativePath.isEmpty() -> server.rootPath
            else -> normalizeRelativePath("${server.rootPath}/${relativePath.trimStart('/')}")
        }
        val encoded = encodePathSegments(fullRel)
        val url = if (encoded.isEmpty()) server.baseUrl else "${server.baseUrl}$encoded"
        return if (asDirectory && !url.endsWith("/")) "$url/" else url
    }

    /** 根据扩展名与探测编码推断精确 MIME Type，确保宿主正确分流原生解码（DSD/APE/WV/ALAC）。 */
    fun resolveMimeType(fileName: String, serverContentType: String = "", detectedCodecMime: String? = null): String {
        if (!detectedCodecMime.isNullOrBlank()) {
            val lower = detectedCodecMime.lowercase(Locale.ROOT)
            if ("alac" in lower) return "audio/alac"
        }
        return when (extensionOf(fileName)) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "alac" -> "audio/alac"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "ape" -> "audio/x-ape"
            "wv" -> "audio/x-wavpack"
            "dsf" -> "audio/x-dsf"
            "dff" -> "audio/x-dff"
            "aiff", "aif" -> "audio/aiff"
            "wma" -> "audio/x-ms-wma"
            "mka" -> "audio/x-matroska"
            "mp2" -> "audio/mpeg"
            "amr" -> "audio/amr"
            else -> serverContentType.substringBefore(';').trim().ifBlank { "audio/*" }
        }
    }

    fun formatLabel(fileName: String, detectedCodecMime: String? = null): String {
        if (detectedCodecMime?.contains("alac", ignoreCase = true) == true) return "alac"
        return extensionOf(fileName).ifBlank { "audio" }
    }

    /** 构造面包屑层级列表：(展示名, 相对路径)。 */
    fun breadcrumbs(currentPath: String, rootLabel: String): List<Pair<String, String>> {
        val normalized = normalizeRelativePath(currentPath)
        val list = mutableListOf(rootLabel to "")
        if (normalized.isEmpty()) return list
        val parts = normalized.trimStart('/').split('/')
        var acc = ""
        for (part in parts) {
            acc = "$acc/$part"
            list += part to acc
        }
        return list
    }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    )
}

/** 没有封面时显示音源色占位；SDK 默认颜色是透明的。 */
internal fun webDavArtwork(url: String?, title: String): SourceArtwork =
    SourceArtwork(url, 0xFF5B93D3, WEBDAV_PAGE_COLOR_ARGB, title.take(1))

/** 用目录封面补齐没有内嵌封面的歌曲。 */
internal fun SourceTrackDetails.withCover(coverUri: String?): SourceTrackDetails =
    if (coverUri.isNullOrBlank() || !artwork.url.isNullOrBlank()) this
    else copy(track = track.copy(artworkUrl = coverUri), artwork = artwork.copy(url = coverUri))

internal fun normalizeRelativePath(raw: String): String = WebDavPaths.normalizeRelativePath(raw)
internal fun fileNameOf(path: String): String = WebDavPaths.fileNameOf(path)

/** 稳定身份哈希工具：服务器地址 + 用户名哈希 + 文件路径，不依赖列表序号或临时地址。 */
internal object WebDavIdentity {
    fun usernameHash(username: String): String =
        sha256Hex(username.trim().ifEmpty { "anonymous" }).take(16)

    fun serverKey(baseUrl: String, rootPath: String, username: String): String {
        val base = WebDavPaths.normalizeBaseUrl(baseUrl)
        val root = WebDavPaths.normalizeRelativePath(rootPath)
        val userHash = usernameHash(username)
        return "wd_" + sha256Hex("$base$root\n$userHash").take(24)
    }

    fun trackStableId(server: WebDavServer, relativePath: String): String {
        val normalizedPath = WebDavPaths.normalizeRelativePath(relativePath)
        val fullPath = if (server.rootPath.isEmpty()) normalizedPath else "${server.rootPath}$normalizedPath"
        val userHash = usernameHash(server.username)
        return "wd_" + sha256Hex("${server.baseUrl}\n$userHash\n$fullPath").take(32)
    }

    fun bookmarkId(server: WebDavServer, dirPath: String, includeSubfolders: Boolean): String {
        val normalized = WebDavPaths.normalizeRelativePath(dirPath)
        val userHash = usernameHash(server.username)
        val scope = if (includeSubfolders) "sub" else "flat"
        return "wdb_" + sha256Hex("${server.baseUrl}${server.rootPath}\n$userHash\n$normalized\n$scope").take(24)
    }

    fun cacheIdentity(server: WebDavServer, relativePath: String, etag: String, sizeBytes: Long): String {
        val base = trackStableId(server, relativePath)
        val fingerprint = sha256Hex("$etag|$sizeBytes").take(12)
        return "$WEBDAV_SOURCE_ID:$base:original:$fingerprint"
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
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
