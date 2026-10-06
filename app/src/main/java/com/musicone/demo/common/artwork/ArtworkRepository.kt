package com.musicone.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal object ArtworkRepository {
    private const val MAX_ARTWORK_SIDE = 768
    private const val LOG_TAG = "MusicOneArtwork"
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun clearMemory() { synchronized(cache) { cache.evictAll() }; ArtistAvatarCache.clear() }

    fun peek(imageUrl: String?, maxSide: Int = MAX_ARTWORK_SIDE): Bitmap? {
        val source = imageUrl.normalizedArtworkUrl() ?: return null
        val key = memoryKey(source, maxSide)
        return synchronized(cache) { cache.get(key) }
    }

    suspend fun load(
        imageUrl: String?,
        forceRefresh: Boolean = false,
        maxSide: Int = MAX_ARTWORK_SIDE,
    ): Bitmap? {
        val source = imageUrl.normalizedArtworkUrl() ?: return null
        val key = memoryKey(source, maxSide)
        if (!forceRefresh) synchronized(cache) { cache.get(key) }?.let { return it }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            if (!forceRefresh) synchronized(cache) { cache.get(key) }?.let { return@withLock it }
            val bitmap = withContext(Dispatchers.IO) {
                if (source.startsWith("file:")) {
                    loadLocalFile(source, maxSide)
                } else {
                    val stored = if (forceRefresh) null else MusicDiskCache.available()?.read("artwork:$source")
                    stored?.let { decodeArtwork(it, maxSide) } ?: download(source, maxSide)
                }
            }
            if (bitmap != null) synchronized(cache) { cache.put(key, bitmap) }
            locks.remove(key, lock)
            bitmap
        }
    }

    private fun loadLocalFile(imageUrl: String, maxSide: Int): Bitmap? = runCatching {
        val uri = URI.create(imageUrl)
        decodeArtwork(File(uri).readBytes(), maxSide)
    }.onFailure { error ->
        Log.w(LOG_TAG, "本地背景加载失败", error)
    }.getOrNull()

    private fun download(imageUrl: String, maxSide: Int): Bitmap? {
        var lastFailure: Throwable? = null
        artworkDownloadCandidates(imageUrl).forEach { candidate ->
            val bitmap = runCatching { downloadCandidate(candidate, maxSide) }
                .onFailure { lastFailure = it }
                .getOrNull()
            if (bitmap != null) return bitmap
            if (lastFailure == null) lastFailure = IOException("图片内容无法解码")
        }
        val uri = runCatching { URI.create(imageUrl) }.getOrNull()
        Log.w(LOG_TAG, "封面加载失败：${uri?.host.orEmpty()}${uri?.path.orEmpty()}", lastFailure)
        return null
    }

    private fun downloadCandidate(imageUrl: String, maxSide: Int): Bitmap? {
        val uri = URI.create(imageUrl)
        return uri.toURL().openConnection().apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MusicOne/0.1")
            if (uri.host?.endsWith("music.126.net") == true || uri.host?.endsWith("music.163.com") == true) {
                setRequestProperty("Referer", "https://music.163.com/")
            }
            if (uri.host?.endsWith("gtimg.cn") == true ||
                uri.host?.endsWith("y.qq.com") == true && uri.path.startsWith("/music/photo_new/")) {
                setRequestProperty("Referer", "https://y.qq.com/")
            }
        }.getInputStream().use { input ->
            val bytes = input.readBytes()
            decodeArtwork(bytes, maxSide)?.also { MusicDiskCache.available()?.write("artwork:$imageUrl", bytes) }
        }
    }

    private fun decodeArtwork(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val largestSide = maxOf(decoded.width, decoded.height)
        if (largestSide <= maxSide) return decoded
        val ratio = maxSide.toFloat() / largestSide
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun memoryKey(source: String, maxSide: Int): String = "$maxSide|$source"
}

@Composable
internal fun rememberArtworkBitmap(
    imageUrl: String?,
    refreshKey: Any? = null,
    maxSide: Int = 768,
): State<Bitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState(
        initialValue = ArtworkRepository.peek(imageUrl, maxSide),
        key1 = imageUrl.normalizedArtworkUrl(),
        key2 = refreshKey to maxSide,
    ) {
        // 先准备磁盘缓存，避免冷启动时背景图因初始化竞态重新走网络。
        withContext(Dispatchers.IO) { MusicDiskCache.get(context) }
        value = ArtworkRepository.load(imageUrl, forceRefresh = refreshKey != null, maxSide = maxSide)
    }
}

private fun String?.normalizedArtworkUrl(): String? = this
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

private val QQ_ALBUM_ARTWORK_URL = Regex(
    "^https?://(?:y\\.gtimg\\.cn|y\\.qq\\.com)/music/photo_new/T002R\\d+x\\d+M000([A-Za-z0-9]+)\\.jpg(?:\\?.*)?$",
    RegexOption.IGNORE_CASE,
)

/** QQ 的旧专辑偶尔会在单一域名或尺寸节点失败，按同一 albumMid 尝试等价 CDN 地址。 */
internal fun artworkDownloadCandidates(imageUrl: String): List<String> {
    val albumMid = QQ_ALBUM_ARTWORK_URL.matchEntire(imageUrl)?.groupValues?.getOrNull(1)
        ?: return listOf(imageUrl)
    return listOf(
        imageUrl,
        "https://y.qq.com/music/photo_new/T002R500x500M000$albumMid.jpg",
        "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg",
        "https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg",
    ).distinct()
}
