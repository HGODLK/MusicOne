package com.musicone

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 给通知栏和系统媒体控件裁一份小尺寸正方形封面，复用缓存，不回收原图。 */
internal suspend fun loadSystemArtwork(uri: String): Bitmap? = withContext(Dispatchers.IO) {
    val original = ArtworkRepository.load(uri, maxSide = 256) ?: return@withContext null
    val side = minOf(original.width, original.height)
    if (original.width == original.height) original else Bitmap.createBitmap(
        original, (original.width - side) / 2, (original.height - side) / 2, side, side,
    )
}
