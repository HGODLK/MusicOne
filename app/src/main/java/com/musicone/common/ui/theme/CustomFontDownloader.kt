package com.musicone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal sealed interface CustomFontLinkTarget {
    data class DirectFile(val originalUrl: String, val suggestedName: String) : CustomFontLinkTarget
    data class GitHubRelease(val owner: String, val repo: String, val tag: String?) : CustomFontLinkTarget
    data class Invalid(val reason: String) : CustomFontLinkTarget
}

internal data class CustomFontRemoteAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

internal data class CustomFontArchiveEntry(
    val entryPath: String,
    val displayName: String,
    val sizeBytes: Long,
    val hint: String?,
)

private val githubBlobRegex = Regex("^/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$")
private val githubRawPathRegex = Regex("^/([^/]+)/([^/]+)/raw/([^/]+)/(.+)$")
private val githubReleaseDownloadRegex = Regex("^/([^/]+)/([^/]+)/releases/(?:download|latest/download)/.+$")
private val githubReleaseTagRegex = Regex("^/([^/]+)/([^/]+)/releases/tag/([^/]+)/?$")
private val githubReleasesRootRegex = Regex("^/([^/]+)/([^/]+)/releases(?:/latest)?/?$")
private val githubRepoRootRegex = Regex("^/([^/]+)/([^/]+)/?$")

private val ghProxySupportedHosts = setOf(
    "github.com",
    "raw.githubusercontent.com",
    "objects.githubusercontent.com",
    "codeload.github.com",
)

/** 解析 GitHub 仓库/Release/blob/raw 或普通 HTTPS 字体直链。 */
internal fun parseCustomFontLink(rawInput: String): CustomFontLinkTarget {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return CustomFontLinkTarget.Invalid("请先粘贴下载链接")
    val normalized = if (trimmed.startsWith("github.com/", ignoreCase = true) ||
        trimmed.startsWith("raw.githubusercontent.com/", ignoreCase = true)
    ) "https://$trimmed" else trimmed
    val uri = runCatching { URI.create(normalized) }.getOrNull()
        ?: return CustomFontLinkTarget.Invalid("链接格式无效，请检查是否包含空格或非法字符")
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "https" && scheme != "http") return CustomFontLinkTarget.Invalid("仅支持 http / https 链接")
    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
    if (host.isEmpty()) return CustomFontLinkTarget.Invalid("链接缺少域名")
    val path = uri.rawPath.orEmpty()

    if (host == "github.com" || host == "www.github.com") {
        githubBlobRegex.matchEntire(path)?.let { m ->
            val rawUrl = "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}"
            return CustomFontLinkTarget.DirectFile(rawUrl, suggestedFileNameFromPath(m.groupValues[4]))
        }
        githubRawPathRegex.matchEntire(path)?.let { m ->
            val rawUrl = "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}"
            return CustomFontLinkTarget.DirectFile(rawUrl, suggestedFileNameFromPath(m.groupValues[4]))
        }
        if (githubReleaseDownloadRegex.matches(path)) {
            return CustomFontLinkTarget.DirectFile(normalized, suggestedFileNameFromPath(path))
        }
        githubReleaseTagRegex.matchEntire(path)?.let { m ->
            return CustomFontLinkTarget.GitHubRelease(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        githubReleasesRootRegex.matchEntire(path)?.let { m ->
            return CustomFontLinkTarget.GitHubRelease(m.groupValues[1], m.groupValues[2], tag = null)
        }
        githubRepoRootRegex.matchEntire(path)?.let { m ->
            return CustomFontLinkTarget.GitHubRelease(m.groupValues[1], m.groupValues[2].removeSuffix(".git"), tag = null)
        }
        return CustomFontLinkTarget.Invalid("无法识别该 GitHub 链接，请粘贴仓库主页、Release 页或字体文件链接")
    }
    if (host == "raw.githubusercontent.com") {
        return CustomFontLinkTarget.DirectFile(normalized, suggestedFileNameFromPath(path))
    }
    val name = suggestedFileNameFromPath(path)
    return CustomFontLinkTarget.DirectFile(normalized, name.ifBlank { "download.ttf" })
}

/** 仅在选用 ghproxy 且目标为 GitHub 文件域名时套用前缀，避免重复拼接或误改非 GitHub 直链。 */
internal fun resolveCustomFontDownloadUrl(
    originalUrl: String,
    channel: CustomFontDownloadChannel,
    ghProxyPrefix: String,
): String {
    if (channel != CustomFontDownloadChannel.GHPROXY) return originalUrl
    val prefix = normalizeGhProxyPrefix(ghProxyPrefix)
    if (originalUrl.startsWith(prefix, ignoreCase = true)) return originalUrl
    val host = runCatching { URI.create(originalUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return originalUrl
    return if (host in ghProxySupportedHosts) "$prefix$originalUrl" else originalUrl
}

internal fun isSupportedFontOrArchiveName(name: String): Boolean {
    val lower = name.trim().lowercase(Locale.ROOT)
    return isSupportedFontFileName(lower) || lower.endsWith(".zip")
}

internal fun isZipFileName(name: String): Boolean = name.trim().lowercase(Locale.ROOT).endsWith(".zip")

internal fun isZipHeader(file: File): Boolean = runCatching {
    val header = ByteArray(4)
    val read = FileInputStream(file).use { it.read(header) }
    read >= 4 && header[0].toInt() == 'P'.code && header[1].toInt() == 'K'.code &&
        (header[2].toInt() == 3 || header[2].toInt() == 5)
}.getOrDefault(false)

internal fun parseGitHubReleaseAssets(jsonText: String): List<CustomFontRemoteAsset> {
    val trimmed = jsonText.trim()
    if (trimmed.isEmpty()) return emptyList()
    val releases = ArrayList<JSONObject>()
    if (trimmed.startsWith("[")) {
        val arr = JSONArray(trimmed)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!obj.optBoolean("draft", false)) releases += obj
        }
    } else releases += JSONObject(trimmed)

    val seen = HashSet<String>()
    val result = ArrayList<CustomFontRemoteAsset>()
    for (release in releases) {
        val assets = release.optJSONArray("assets") ?: continue
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            val size = asset.optLong("size", 0L).coerceAtLeast(0L)
            if (name.isEmpty() || url.isEmpty() || !isSupportedFontOrArchiveName(name)) continue
            if (seen.add(url)) result += CustomFontRemoteAsset(name, url, size)
        }
        if (result.isNotEmpty()) break
    }
    return result
}

internal fun inferFontArchiveHint(entryPath: String): String? {
    val lower = entryPath.lowercase(Locale.ROOT)
    val tags = ArrayList<String>(3)
    when {
        "semibold" in lower || "demibold" in lower -> tags += "半粗"
        "bold" in lower -> tags += "粗体"
        "medium" in lower -> tags += "中等"
        "regular" in lower -> tags += "常规"
        "light" in lower -> tags += "细体"
    }
    if ("variable" in lower || "-vf" in lower || "[wght]" in lower) tags += "可变"
    when {
        "arabic" in lower || "vazirmatn" in lower || "naskh" in lower || "persian" in lower -> tags += "阿拉伯/波斯"
        Regex("(^|[-_./ ])(sc|hans|cn)([-_./ ]|$)").containsMatchIn(lower) -> tags += "简中"
        Regex("(^|[-_./ ])(tc|hant|tw|hk)([-_./ ]|$)").containsMatchIn(lower) -> tags += "繁中"
        Regex("(^|[-_./ ])(jp|ja)([-_./ ]|$)").containsMatchIn(lower) -> tags += "日文"
        Regex("(^|[-_./ ])(kr|ko)([-_./ ]|$)").containsMatchIn(lower) -> tags += "韩文"
    }
    return tags.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun isSafeZipEntryPath(path: String): Boolean {
    if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")) return false
    return path.split('/', '\\').none { it == ".." }
}

internal fun inspectFontZip(zipFile: File): List<CustomFontArchiveEntry> {
    val result = ArrayList<CustomFontArchiveEntry>()
    ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            try {
                val path = entry.name.replace('\\', '/')
                if (entry.isDirectory || !isSafeZipEntryPath(path)) continue
                val fileName = path.substringAfterLast('/')
                if (fileName.startsWith(".") || path.contains("__MACOSX/") || !isSupportedFontFileName(fileName)) continue
                result += CustomFontArchiveEntry(path, cleanFontDisplayName(fileName), entry.size.coerceAtLeast(0L), inferFontArchiveHint(path))
            } finally {
                zip.closeEntry()
            }
        }
    }
    return result.sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.entryPath }))
}

internal suspend fun extractAndImportFontZipEntries(
    context: Context,
    zipFile: File,
    selectedPaths: Set<String>,
): List<CustomFontRecord> = withContext(Dispatchers.IO) {
    val imported = ArrayList<CustomFontRecord>()
    var totalExtracted = 0L
    ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
        while (true) {
            coroutineContext.ensureActive()
            val entry = zip.nextEntry ?: break
            try {
                val normalized = entry.name.replace('\\', '/')
                if (entry.isDirectory || !isSafeZipEntryPath(normalized) || normalized !in selectedPaths) continue
                val fileName = normalized.substringAfterLast('/')
                val temp = File(context.cacheDir, "font_zip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.tmp")
                try {
                    totalExtracted += copyBounded(zip, temp, CUSTOM_FONT_MAX_FILE_BYTES)
                    require(totalExtracted <= CUSTOM_FONT_MAX_ARCHIVE_BYTES) {
                        "本次解压字体总大小超过 ${formatFontFileSize(CUSTOM_FONT_MAX_ARCHIVE_BYTES)} 上限"
                    }
                    imported += CustomFontStore.importValidatedFile(context, temp, fileName)
                } finally {
                    temp.delete()
                }
            } finally {
                zip.closeEntry()
            }
        }
    }
    imported
}

internal suspend fun fetchGitHubFontReleaseAssets(target: CustomFontLinkTarget.GitHubRelease): List<CustomFontRemoteAsset> =
    withContext(Dispatchers.IO) {
        val apiUrls = if (target.tag != null) {
            listOf("https://api.github.com/repos/${target.owner}/${target.repo}/releases/tags/${target.tag}")
        } else {
            listOf(
                "https://api.github.com/repos/${target.owner}/${target.repo}/releases/latest",
                "https://api.github.com/repos/${target.owner}/${target.repo}/releases?per_page=5",
            )
        }
        var lastError: String? = null
        for (url in apiUrls) {
            coroutineContext.ensureActive()
            val response = runCatching {
                PlatformHttp.get(url, headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "MusicOne-Android"))
            }.getOrElse { error ->
                lastError = "无法连接 GitHub API：${error.message ?: error.javaClass.simpleName}（可直接粘贴文件或 Release 下载链接）"
                continue
            }
            if (response.status == 200) {
                val assets = parseGitHubReleaseAssets(response.text)
                if (assets.isNotEmpty()) return@withContext assets
                lastError = "该 Release 下未找到 .ttf / .otf / .ttc / .zip 字体附件"
            } else if (response.status == 403) {
                lastError = "GitHub API 访问受限（HTTP 403），建议直接粘贴字体或 ZIP 下载链接"
            } else {
                lastError = "未找到对应 Release（HTTP ${response.status}）"
            }
        }
        error(lastError ?: "未找到可下载的字体附件")
    }

internal suspend fun downloadCustomFontToTemp(
    context: Context,
    url: String,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): Pair<File, String> = withContext(Dispatchers.IO) {
    var currentUrl = url
    var connection: HttpURLConnection? = null
    val temp = File(context.cacheDir, "font_dl_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.tmp")
    try {
        var redirects = 0
        while (true) {
            coroutineContext.ensureActive()
            val conn = (URI.create(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MusicOne-Android")
            }
            connection = conn
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= 5) error("重定向次数过多或地址无效")
                currentUrl = URI.create(currentUrl).resolve(location).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                error("下载失败（HTTP $code），可切换 ghproxy / 直连通道后重试")
            }
            val totalBytes = conn.contentLengthLong
            if (totalBytes > CUSTOM_FONT_MAX_ARCHIVE_BYTES) {
                conn.disconnect()
                error("远程文件超过 ${formatFontFileSize(CUSTOM_FONT_MAX_ARCHIVE_BYTES)} 上限")
            }
            val suggestedName = parseContentDispositionFileName(conn.getHeaderField("Content-Disposition"))
                ?: suggestedFileNameFromPath(URI.create(currentUrl).rawPath.orEmpty())
            var downloaded = 0L
            val buffer = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        if (downloaded > CUSTOM_FONT_MAX_ARCHIVE_BYTES) {
                            error("下载文件超过 ${formatFontFileSize(CUSTOM_FONT_MAX_ARCHIVE_BYTES)} 上限")
                        }
                        output.write(buffer, 0, read)
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
            conn.disconnect()
            return@withContext temp to suggestedName
        }
    } catch (error: Throwable) {
        connection?.disconnect()
        temp.delete()
        throw error
    }
    error("下载意外中止")
}

private fun suggestedFileNameFromPath(rawPath: String): String {
    val tail = rawPath.substringAfterLast('/').substringBefore('?')
    return runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
}

private fun parseContentDispositionFileName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
        return runCatching { URLDecoder.decode(m.groupValues[1].trim(), "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}

internal fun formatFontFileSize(bytes: Long): String = when {
    bytes <= 0L -> "未知大小"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
}
