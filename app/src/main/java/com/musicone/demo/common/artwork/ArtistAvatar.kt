package com.musicone.demo

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 小头像保留少量独立预算，避免大封面淘汰后每次打开菜单都重新解码。 */
internal object ArtistAvatarCache {
    private val images = object : LruCache<String, Bitmap>(2 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    fun peek(url: String?): Bitmap? = url?.let {
        images.get(it) ?: ArtworkRepository.peek(it, 192) ?: ArtworkRepository.peek(it)
    }
    fun clear() = images.evictAll()
    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return peek(url) ?: ArtworkRepository.load(url, maxSide = 192)?.also { images.put(url, it) }
    }
}

/** 菜单、详情和飞行层共享图片及混合比例，加载期间不绘制占位头像。 */
internal class ArtistAvatarPresentation(val url: String?) {
    var bitmap by mutableStateOf(ArtistAvatarCache.peek(url)); private set
    val imageAlpha = Animatable(if (bitmap != null) 1f else 0f)
    val placeholderAlpha = Animatable(0f)
    private val loading = Mutex()

    suspend fun prepare() = loading.withLock {
        if (bitmap != null && imageAlpha.value == 1f) return@withLock
        val loaded = bitmap ?: ArtistAvatarCache.load(url)
        if (loaded != null) bitmap = loaded
        coroutineScope {
            launch { imageAlpha.animateTo(if (loaded != null) 1f else 0f, musicMotion(280)) }
            placeholderAlpha.animateTo(if (loaded == null) 1f else 0f, musicMotion(280))
        }
    }
}

@Composable
internal fun rememberArtistAvatar(url: String?): ArtistAvatarPresentation {
    val avatar = remember(url) { ArtistAvatarPresentation(url) }
    PrepareArtistAvatar(avatar)
    return avatar
}

@Composable
internal fun PrepareArtistAvatar(avatar: ArtistAvatarPresentation) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(avatar) {
        withContext(Dispatchers.IO) { MusicDiskCache.get(context) }
        avatar.prepare()
    }
}

@Composable
internal fun ArtistAvatar(avatar: ArtistAvatarPresentation, mark: String, modifier: Modifier, markSize: TextUnit) {
    Box(modifier.clip(CircleShape)) {
        Artwork(0xFFCEDCD7, 0xFF779187, mark,
            Modifier.fillMaxSize().graphicsLayer { alpha = avatar.placeholderAlpha.value },
            markSize, Alignment.Center, CircleShape)
        avatar.bitmap?.let { bitmap ->
            Image(bitmap.asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer { alpha = avatar.imageAlpha.value },
                contentScale = ContentScale.Crop)
        }
    }
}
