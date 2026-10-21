package com.musicone.demo

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 系统通知使用小尺寸正方形封面，复用缓存且不回收仓库持有的原图。 */
internal suspend fun loadSystemArtwork(uri: String): Bitmap? = withContext(Dispatchers.IO) {
    val original = ArtworkRepository.load(uri, maxSide = 256) ?: return@withContext null
    val side = minOf(original.width, original.height)
    if (original.width == original.height) original else Bitmap.createBitmap(
        original, (original.width - side) / 2, (original.height - side) / 2, side, side,
    )
}
