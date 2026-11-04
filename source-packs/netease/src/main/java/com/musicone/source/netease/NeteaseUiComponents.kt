package com.musicone.source.netease

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private val images = object : LruCache<String, Bitmap>(16 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
}
@Composable internal fun rememberArtworkBitmap(url: String?, refreshKey: Int? = null, maxSide: Int = 640): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(null, url, refreshKey, maxSide) {
        if (url.isNullOrBlank()) return@produceState
        val key = "$url|$refreshKey|$maxSide"
        images.get(key)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val bytes = if (url.startsWith("http")) {
                    val connection = URI.create(url.asNeteaseHttpsUrl()).toURL().openConnection() as HttpURLConnection
                    try { connection.connectTimeout = 12_000; connection.readTimeout = 25_000; connection.inputStream.use { it.readBytes() } }
                    finally { connection.disconnect() }
                } else context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() } ?: return@runCatchingCancellable null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { images.put(key, it) }
            }.getOrNull()
        }
    }
}
@Composable internal fun <T> musicMotion(responseMillis: Int = 320): FiniteAnimationSpec<T> =
    if (responseMillis <= 0 || LocalNeteaseReduceMotion.current) snap() else tween(responseMillis, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
internal fun musicOneUiAnnotatedString(text: String) = AnnotatedString(text)
internal object ArtworkColorSampler {
    fun sampleBitmap(bitmap: Bitmap): IntArray = IntArray(9) { index ->
        bitmap.getPixel(((index % 3 * 2 + 1) * bitmap.width / 6).coerceIn(0, bitmap.width - 1),
            ((index / 3 * 2 + 1) * bitmap.height / 6).coerceIn(0, bitmap.height - 1))
    }
}
@Composable internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable internal fun AccountAvatar(account: NeteaseAccount?, size: Dp) {
    val bitmap by rememberArtworkBitmap(account?.avatarUrl)
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "账号头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, "账号头像", Modifier.size(size * .5f))
    }
}
@Composable internal fun PlaylistSyncedArtwork(bitmap: Bitmap?, awaitingArtwork: Boolean, imageUrl: String?, start: Long, end: Long,
    mark: String, modifier: Modifier, markSize: TextUnit, shape: androidx.compose.ui.graphics.Shape, sourceKey: String) {
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(start), Color(end)))), contentAlignment = Alignment.Center) {
        Crossfade(bitmap, animationSpec = musicMotion(240), label = "网易云歌单封面") { ready ->
            if (ready != null) Image(ready.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(mark, fontSize = markSize, color = Color.White) }
        }
    }
}
