package com.musicone.demo

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

internal object ArtworkColorSampler {
    const val GRID_SIDE = 3

    fun sampleBitmap(artwork: Bitmap): IntArray = IntArray(GRID_SIDE * GRID_SIDE) { index ->
        val x = ((index % GRID_SIDE * 2 + 1) * artwork.width / (GRID_SIDE * 2))
            .coerceIn(0, artwork.width - 1)
        val y = ((index / GRID_SIDE * 2 + 1) * artwork.height / (GRID_SIDE * 2))
            .coerceIn(0, artwork.height - 1)
        artwork.getPixel(x, y)
    }

    fun placeholder(identity: ArtworkIdentity): IntArray = IntArray(GRID_SIDE * GRID_SIDE) { index ->
        val x = index % GRID_SIDE
        val y = index / GRID_SIDE
        lerp(Color(identity.start), Color(identity.end), (x + y).toFloat() / ((GRID_SIDE - 1) * 2)).toArgb()
    }
}

/** 真实封面显示前就缓存 3×3 九个颜色，播放页转场只读取现成采样。 */
internal object ArtworkColorFieldRepository {
    private val cache = object : LruCache<ArtworkIdentity, IntArray>(48) {}

    fun peek(identity: ArtworkIdentity): IntArray? = synchronized(cache) { cache.get(identity) }

    fun prepare(identity: ArtworkIdentity, artwork: Bitmap?): IntArray? {
        artwork ?: return null
        peek(identity)?.let { return it }
        val field = ArtworkColorSampler.sampleBitmap(artwork)
        synchronized(cache) {
            cache.get(identity)?.let { return it }
            cache.put(identity, field)
        }
        return field
    }
}
