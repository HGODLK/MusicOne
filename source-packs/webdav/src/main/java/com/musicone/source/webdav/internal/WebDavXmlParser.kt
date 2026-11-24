package com.musicone.source.webdav.internal

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.util.Locale

/**
 * 解析 WebDAV PROPFIND 返回的 207 Multi-Status XML。
 * 兼容 OpenList/AList、坚果云、Nextcloud、Nginx、Apache 等多种命名空间前缀与路径编码格式。
 */
internal object WebDavXmlParser {
    private data class RawResponse(
        val href: String,
        val displayName: String,
        val isCollection: Boolean,
        val contentLength: Long,
        val etag: String,
        val lastModified: String,
        val contentType: String,
        val okStatus: Boolean,
    )

    fun parseDirectoryListing(
        xml: String,
        baseUrl: String,
        rootPath: String,
        requestedRelativePath: String,
    ): WebDavDirectoryListing {
        val rawItems = parseMultiStatus(xml)
        val basePath = extractBasePath(baseUrl, rootPath)
        val normalizedReq = WebDavPaths.normalizeRelativePath(requestedRelativePath)

        val dirs = mutableListOf<WebDavEntry>()
        val audios = mutableListOf<WebDavEntry>()
        val lyrics = LinkedHashMap<String, WebDavEntry>()
        val images = mutableListOf<WebDavEntry>()
        val seenPaths = HashSet<String>()

        for (raw in rawItems) {
            if (!raw.okStatus) continue
            val decodedFullPath = extractDecodedPath(raw.href) ?: continue
            val relative = stripBasePath(decodedFullPath, basePath) ?: continue
            val normalized = WebDavPaths.normalizeRelativePath(relative)
            // 排除当前请求目录自身，以及重复条目
            if (normalized == normalizedReq || !seenPaths.add(normalized)) continue
            val name = raw.displayName.trim().ifEmpty { WebDavPaths.fileNameOf(normalized) }
            if (name.isEmpty() || name.startsWith("._")) continue

            val entry = WebDavEntry(
                path = normalized,
                name = name,
                isDirectory = raw.isCollection,
                sizeBytes = raw.contentLength,
                etag = raw.etag.trim().trim('"'),
                lastModified = raw.lastModified.trim(),
                contentType = raw.contentType.trim(),
            )
            when {
                entry.isDirectory -> dirs += entry
                WebDavPaths.isAudioFile(entry.name) -> audios += entry
                WebDavPaths.isLyricFile(entry.name) -> {
                    val stem = WebDavPaths.stemOf(entry.name).lowercase(Locale.ROOT)
                    lyrics.putIfAbsent(stem, entry)
                }
                WebDavPaths.isImageFile(entry.name) -> images += entry
            }
        }

        dirs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        audios.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val cover = pickCoverFile(images)

        return WebDavDirectoryListing(
            path = normalizedReq,
            directories = dirs,
            audioFiles = audios,
            lyricFilesByStem = lyrics,
            coverFile = cover,
        )
    }

    private fun pickCoverFile(images: List<WebDavEntry>): WebDavEntry? {
        if (images.isEmpty()) return null
        val byStem = images.associateBy { WebDavPaths.stemOf(it.name).lowercase(Locale.ROOT) }
        for (preferred in WEBDAV_COVER_BASENAMES) {
            byStem[preferred]?.let { return it }
        }
        return null
    }

    private fun extractBasePath(baseUrl: String, rootPath: String): String {
        val uriPath = runCatching { URI(baseUrl).rawPath }.getOrNull().orEmpty()
        val decodedUriPath = WebDavPaths.decodePercentPath(uriPath)
        val combined = if (rootPath.isEmpty()) decodedUriPath else "$decodedUriPath/$rootPath"
        return WebDavPaths.normalizeRelativePath(combined)
    }

    private fun extractDecodedPath(href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        val rawPath = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            runCatching { URI(trimmed).rawPath }.getOrNull()
                ?: trimmed.substringAfter("://").substringAfter('/', "").let { "/$it" }
        } else {
            trimmed.substringBefore('?').substringBefore('#')
        }
        return WebDavPaths.normalizeRelativePath(WebDavPaths.decodePercentPath(rawPath))
    }

    private fun stripBasePath(fullPath: String, basePath: String): String? {
        if (basePath.isEmpty()) return fullPath
        if (fullPath == basePath) return ""
        if (fullPath.startsWith("$basePath/")) return fullPath.removePrefix(basePath)
        // 某些服务器 href 大小写或前缀略异时再做不区分大小写匹配
        if (fullPath.equals(basePath, ignoreCase = true)) return ""
        if (fullPath.startsWith("$basePath/", ignoreCase = true)) return fullPath.substring(basePath.length)
        return null
    }

    private fun parseMultiStatus(xml: String): List<RawResponse> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val items = mutableListOf<RawResponse>()
        var inResponse = false
        var inPropStat = false
        var inResourceType = false

        var href = ""
        var displayName = ""
        var isCollection = false
        var contentLength = -1L
        var etag = ""
        var lastModified = ""
        var contentType = ""
        var propStatStatusOk = true
        var anyOkPropStat = false

        // 单个 propstat 临时收集，避免 404 propstat 覆盖有效属性
        var pDisplayName = ""
        var pIsCollection = false
        var pContentLength = -1L
        var pEtag = ""
        var pLastModified = ""
        var pContentType = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localName(parser)
                    when {
                        tag == "response" -> {
                            inResponse = true
                            href = ""
                            displayName = ""
                            isCollection = false
                            contentLength = -1L
                            etag = ""
                            lastModified = ""
                            contentType = ""
                            anyOkPropStat = false
                        }
                        inResponse && tag == "href" && !inPropStat -> {
                            href = readTextSafe(parser)
                        }
                        inResponse && tag == "propstat" -> {
                            inPropStat = true
                            propStatStatusOk = true
                            pDisplayName = ""
                            pIsCollection = false
                            pContentLength = -1L
                            pEtag = ""
                            pLastModified = ""
                            pContentType = ""
                        }
                        inPropStat && tag == "status" -> {
                            val statusText = readTextSafe(parser)
                            propStatStatusOk = is2xxStatus(statusText)
                        }
                        inPropStat && tag == "displayname" -> {
                            pDisplayName = readTextSafe(parser)
                        }
                        inPropStat && tag == "resourcetype" -> {
                            inResourceType = true
                        }
                        inResourceType && tag == "collection" -> {
                            pIsCollection = true
                        }
                        inPropStat && tag == "getcontentlength" -> {
                            pContentLength = readTextSafe(parser).trim().toLongOrNull() ?: -1L
                        }
                        inPropStat && tag == "getetag" -> {
                            pEtag = readTextSafe(parser)
                        }
                        inPropStat && tag == "getlastmodified" -> {
                            pLastModified = readTextSafe(parser)
                        }
                        inPropStat && tag == "getcontenttype" -> {
                            pContentType = readTextSafe(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = localName(parser)
                    when {
                        tag == "resourcetype" -> inResourceType = false
                        tag == "propstat" && inPropStat -> {
                            inPropStat = false
                            if (propStatStatusOk) {
                                anyOkPropStat = true
                                if (pDisplayName.isNotEmpty()) displayName = pDisplayName
                                if (pIsCollection) isCollection = true
                                if (pContentLength >= 0) contentLength = pContentLength
                                if (pEtag.isNotEmpty()) etag = pEtag
                                if (pLastModified.isNotEmpty()) lastModified = pLastModified
                                if (pContentType.isNotEmpty()) contentType = pContentType
                            }
                        }
                        tag == "response" && inResponse -> {
                            inResponse = false
                            if (href.isNotEmpty()) {
                                items += RawResponse(
                                    href = href,
                                    displayName = displayName,
                                    isCollection = isCollection,
                                    contentLength = contentLength,
                                    etag = etag,
                                    lastModified = lastModified,
                                    contentType = contentType,
                                    okStatus = anyOkPropStat,
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun localName(parser: XmlPullParser): String {
        val raw = parser.name ?: return ""
        return raw.substringAfter(':').lowercase(Locale.ROOT)
    }

    private fun readTextSafe(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text.orEmpty())
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString()
    }

    private fun is2xxStatus(statusLine: String): Boolean {
        val match = Regex("\\b(2\\d\\d)\\b").find(statusLine)
        return match != null
    }
}
