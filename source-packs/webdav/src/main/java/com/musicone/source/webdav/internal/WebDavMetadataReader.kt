package com.musicone.source.webdav.internal

import android.media.MediaMetadataRetriever
import com.musicone.source.sdk.SourceArtwork
import com.musicone.source.sdk.SourceAudioQuality
import com.musicone.source.sdk.SourceTrack
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.source.sdk.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 音频标签与封面读取器：
 * - 列表初始先由文件名快速构造曲目（支持“歌手 - 歌名”拆分）；
 * - 后台限并发调用 MediaMetadataRetriever（带鉴权头）提取标题、歌手、专辑、时长和内嵌封面；
 * - 无内嵌封面时回退到同目录 cover.jpg / folder.jpg；
 * - 结果按路径 + ETag + 大小持久化缓存，二次打开秒出。
 */
internal class WebDavMetadataReader(
    private val client: WebDavClient,
    private val store: WebDavStore,
) {
    private val concurrency = Semaphore(3)

    /** 快速从文件名与已有缓存构建曲目（零网络阻塞）。 */
    fun buildQuickTrack(
        server: WebDavServer,
        entry: WebDavEntry,
        lyricEntry: WebDavEntry?,
        coverEntry: WebDavEntry?,
    ): Pair<SourceTrackDetails, Boolean> {
        val cached = store.getMetadata(server.key, entry)
        val meta = cached ?: parseMetaFromFileName(entry)
        return buildTrackDetails(server, entry, meta, lyricEntry, coverEntry) to (cached != null)
    }

    /** 联网补全单首歌曲的标签和封面，并写入磁盘缓存。 */
    suspend fun enrichEntry(
        server: WebDavServer,
        entry: WebDavEntry,
        lyricEntry: WebDavEntry?,
        coverEntry: WebDavEntry?,
        folderCoverUri: String?,
    ): SourceTrackDetails = concurrency.withPermit {
        store.getMetadata(server.key, entry)?.let { cached ->
            val withFolderCover = if (cached.artworkFileUri.isNullOrBlank() && !folderCoverUri.isNullOrBlank()) {
                cached.copy(artworkFileUri = folderCoverUri).also { store.putMetadata(server.key, entry.path, it) }
            } else {
                cached
            }
            return@withPermit buildTrackDetails(server, entry, withFolderCover, lyricEntry, coverEntry)
        }

        val fallback = parseMetaFromFileName(entry)
        val extracted = if (supportsAndroidRetriever(entry.name)) {
            readWithMediaMetadataRetriever(server, entry, fallback)
        } else {
            fallback
        }

        val resolvedArtwork = extracted.artworkFileUri ?: folderCoverUri ?: coverEntry?.let {
            ensureFolderCover(server, it)
        }
        val finalMeta = extracted.copy(
            artworkFileUri = resolvedArtwork,
            etag = entry.etag,
            sizeBytes = entry.sizeBytes,
            lastModified = entry.lastModified,
        )
        store.putMetadata(server.key, entry.path, finalMeta)
        buildTrackDetails(server, entry, finalMeta, lyricEntry, coverEntry)
    }

    /** 下载目录共享封面（cover.jpg / folder.jpg），命中本地文件直接复用。 */
    suspend fun ensureFolderCover(server: WebDavServer, coverEntry: WebDavEntry?): String? {
        if (coverEntry == null) return null
        val target = store.coverFileForDirectory(server.key, coverEntry.path, coverEntry.etag, coverEntry.sizeBytes)
        if (target.isFile && target.length() > 0) return target.toURI().toString()
        val ok = runCatching { client.downloadToFile(server, coverEntry.path, target) }.getOrDefault(false)
        return if (ok && target.isFile) target.toURI().toString() else null
    }

    fun flushCache() = store.flushMetadata()

    private suspend fun readWithMediaMetadataRetriever(
        server: WebDavServer,
        entry: WebDavEntry,
        fallback: WebDavTrackMeta,
    ): WebDavTrackMeta = withContext(Dispatchers.IO) {
        val resolved = try {
            client.resolveMediaUrl(server, entry.path, probeNetwork = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext fallback
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(resolved.url, resolved.headers)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim().orEmpty().ifEmpty { fallback.title }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim().orEmpty().ifEmpty {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim().orEmpty()
                }.ifEmpty { fallback.artist }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim().orEmpty().ifEmpty { fallback.album }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.trim()?.takeIf { it.isNotEmpty() }

            var artworkUri: String? = null
            val pic = runCatching { retriever.embeddedPicture }.getOrNull()
            if (pic != null && pic.isNotEmpty()) {
                val coverFile = store.coverFileForEmbedded(server.key, entry.path, entry.etag, entry.sizeBytes)
                if (writeCoverBytes(coverFile, pic)) {
                    artworkUri = coverFile.toURI().toString()
                }
            }

            WebDavTrackMeta(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkFileUri = artworkUri,
                detectedCodecMime = mime,
                etag = entry.etag,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fallback
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeCoverBytes(target: File, bytes: ByteArray): Boolean = runCatching {
        if (target.isFile && target.length() > 0) return true
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    fun buildTrackDetails(
        server: WebDavServer,
        entry: WebDavEntry,
        meta: WebDavTrackMeta,
        lyricEntry: WebDavEntry?,
        coverEntry: WebDavEntry?,
    ): SourceTrackDetails {
        val stableId = WebDavIdentity.trackStableId(server, entry.path)
        val identity = TrackIdentity(WEBDAV_SOURCE_ID, server.accountId, stableId)
        val artistsList = splitArtists(meta.artist)
        val payload = WebDavTrackPayload(
            serverKey = server.key,
            path = entry.path,
            fileName = entry.name,
            etag = entry.etag,
            sizeBytes = entry.sizeBytes,
            lastModified = entry.lastModified,
            contentType = entry.contentType,
            lyricPath = lyricEntry?.path,
            coverPath = coverEntry?.path,
            detectedCodecMime = meta.detectedCodecMime,
        ).toSourcePayload()

        val mime = WebDavPaths.resolveMimeType(entry.name, entry.contentType, meta.detectedCodecMime)
        val quality = SourceAudioQuality(
            id = WEBDAV_QUALITY_ORIGINAL,
            label = "原始文件",
            mimeType = mime,
            available = true,
        )
        val artwork = webDavArtwork(meta.artworkFileUri, meta.title)
        val track = SourceTrack(
            identity = identity,
            title = meta.title,
            artists = artistsList,
            artworkUrl = meta.artworkFileUri,
        )
        return SourceTrackDetails(
            track = track,
            stableId = stableId,
            album = meta.album,
            durationMs = meta.durationMs,
            artwork = artwork,
            access = TrackAccessInfo(playable = true),
            qualities = listOf(quality),
            payload = payload,
            artistsText = artistsList.joinToString(" / "),
        )
    }

    companion object {
        internal const val WEBDAV_QUALITY_ORIGINAL = "original"

        /** Android MediaMetadataRetriever 不支持 DSD/APE/WavPack 等格式，跳过远程探测以免卡慢。 */
        private val RETRIEVER_UNSUPPORTED_EXT = setOf("dsf", "dff", "ape", "wv", "wma")

        fun supportsAndroidRetriever(fileName: String): Boolean =
            WebDavPaths.extensionOf(fileName) !in RETRIEVER_UNSUPPORTED_EXT

        /** 从文件名智能推导初步标题和歌手（如 “周杰伦 - 晴天.flac” 或 “01. 晴天.mp3”）。 */
        fun parseMetaFromFileName(entry: WebDavEntry): WebDavTrackMeta {
            val rawStem = WebDavPaths.stemOf(entry.name).trim()
            // 去除常见音轨序号前缀如 "01. "、"01 - "
            val cleaned = rawStem.replace(Regex("^\\d{1,3}[.\\-_\\s]+(?=\\S)"), "").ifBlank { rawStem }
            val dashSplit = cleaned.split(" - ", limit = 2)
            val (artist, title) = if (dashSplit.size == 2 && dashSplit[0].isNotBlank() && dashSplit[1].isNotBlank()) {
                dashSplit[0].trim() to dashSplit[1].trim()
            } else {
                "" to cleaned
            }
            val parentFolder = WebDavPaths.fileNameOf(WebDavPaths.parentPath(entry.path))
            return WebDavTrackMeta(
                title = title.ifBlank { entry.name },
                artist = artist,
                album = parentFolder,
                etag = entry.etag,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
            )
        }

        private fun splitArtists(raw: String): List<String> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptyList()
            return trimmed.split(Regex("\\s*(?:/|;|、)\\s*"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
    }
}
