package com.musicone.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

internal sealed interface NeteaseBackgroundTarget {
    data object Global : NeteaseBackgroundTarget
    data class Playlist(val playlistId: String) : NeteaseBackgroundTarget
}

internal data class NeteaseCropSelection(
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val zoom: Float = 1f,
    val aspectRatio: Float,
)

internal data class NeteaseCropRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun neteaseCropRect(
    sourceWidth: Int,
    sourceHeight: Int,
    selection: NeteaseCropSelection,
): NeteaseCropRect {
    require(sourceWidth > 0 && sourceHeight > 0)
    val aspect = selection.aspectRatio.coerceIn(.2f, 5f)
    val sourceAspect = sourceWidth.toFloat() / sourceHeight
    val baseWidth = if (sourceAspect > aspect) sourceHeight * aspect else sourceWidth.toFloat()
    val baseHeight = if (sourceAspect > aspect) sourceHeight.toFloat() else sourceWidth / aspect
    val zoom = selection.zoom.coerceIn(1f, 6f)
    val width = (baseWidth / zoom).roundToInt().coerceIn(1, sourceWidth)
    val height = (baseHeight / zoom).roundToInt().coerceIn(1, sourceHeight)
    val centerX = (selection.centerX.coerceIn(0f, 1f) * sourceWidth)
        .coerceIn(width / 2f, sourceWidth - width / 2f)
    val centerY = (selection.centerY.coerceIn(0f, 1f) * sourceHeight)
        .coerceIn(height / 2f, sourceHeight - height / 2f)
    return NeteaseCropRect(
        x = (centerX - width / 2f).roundToInt().coerceIn(0, sourceWidth - width),
        y = (centerY - height / 2f).roundToInt().coerceIn(0, sourceHeight - height),
        width = width,
        height = height,
    )
}

/**
 * 自定义背景保存在应用私有目录，不依赖图片选择器的临时 Uri 权限。
 * 导入时先缩小，避免每次进入“我的”都解码相机原图。
 */
internal object NeteaseProfileBackgroundStore {
    private const val DIRECTORY = "profile"
    private const val FILE_NAME = "netease_custom_background.webp"
    private const val MAX_SIDE = 2_048

    fun currentUri(context: Context): String? = backgroundFile(context)
        .takeIf { it.isFile && it.length() > 0L }
        ?.let(Uri::fromFile)
        ?.toString()

    fun playlistUri(context: Context, playlistId: String): String? =
        backgroundFile(context, NeteaseBackgroundTarget.Playlist(playlistId))
            .takeIf { it.isFile && it.length() > 0L }
            ?.let(Uri::fromFile)
            ?.toString()

    suspend fun loadPreview(context: Context, source: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decodeForScreen(context, source) }.getOrNull()
    }

    suspend fun saveCrop(
        context: Context,
        target: NeteaseBackgroundTarget,
        source: Bitmap,
        selection: NeteaseCropSelection,
    ): Boolean = withContext(Dispatchers.IO) {
        val rect = neteaseCropRect(source.width, source.height, selection)
        val cropped = runCatching {
            Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
        }.getOrNull() ?: return@withContext false
        try {
            writeBitmap(backgroundFile(context, target), cropped)
        } finally {
            if (cropped !== source) cropped.recycle()
        }
    }

    suspend fun import(context: Context, source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeForScreen(context, source) ?: return@runCatching false
            try {
                val aspect = context.resources.displayMetrics.run { widthPixels.toFloat() / heightPixels.coerceAtLeast(1) }
                saveCrop(context, NeteaseBackgroundTarget.Global, bitmap, NeteaseCropSelection(aspectRatio = aspect))
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
    }

    fun clear(context: Context): Boolean {
        return clear(context, NeteaseBackgroundTarget.Global)
    }

    fun clear(context: Context, backgroundTarget: NeteaseBackgroundTarget): Boolean {
        val target = backgroundFile(context, backgroundTarget)
        return !target.exists() || target.delete()
    }

    private fun backgroundFile(context: Context): File =
        File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private fun backgroundFile(context: Context, target: NeteaseBackgroundTarget): File = when (target) {
        NeteaseBackgroundTarget.Global -> backgroundFile(context)
        is NeteaseBackgroundTarget.Playlist -> {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(target.playlistId.toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
            File(File(context.filesDir, DIRECTORY), "netease_playlist_$digest.webp")
        }
    }

    private fun writeBitmap(target: File, bitmap: Bitmap): Boolean {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            FileOutputStream(temporary).use { output ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                check(bitmap.compress(format, 90, output)) { "背景图压缩失败" }
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "背景图保存失败" }
            }
            true
        }.onFailure { temporary.delete() }.getOrDefault(false)
    }

    private fun decodeForScreen(context: Context, source: Uri): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val imageSource = ImageDecoder.createSource(context.contentResolver, source)
            ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val largest = maxOf(width, height)
                if (largest > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / largest
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
    }
}

internal fun neteaseProfileBackgroundSource(customBackgroundUri: String?): String? =
    customBackgroundUri?.takeIf(String::isNotBlank)
