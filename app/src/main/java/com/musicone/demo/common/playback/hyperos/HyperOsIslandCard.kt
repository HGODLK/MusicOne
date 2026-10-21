package com.musicone.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews

/** 只在封面变化时生成并缓存高斯背景，逐秒进度更新不重复处理图片。 */
internal class HyperOsIslandCard(private val context: Context) {
    private var source: Bitmap? = null
    private var background: Bitmap? = null
    private var album: Bitmap? = null
    var foreground = HyperOsCardContrast.WHITE
        private set

    fun render(frame: HyperOsIslandFrame, cover: Bitmap?): RemoteViews {
        if (background == null || cover !== source) prepare(cover)
        return RemoteViews(context.packageName, R.layout.hyper_os_island_card).apply {
            setImageViewBitmap(R.id.island_background, background)
            if (album != null) setImageViewBitmap(R.id.island_cover, album)
            else setImageViewResource(R.id.island_cover, R.drawable.ic_island_album_placeholder)
            setTextViewText(R.id.island_title, frame.title)
            setTextViewText(R.id.island_artist, frame.artist)
            setTextViewText(R.id.island_elapsed, frame.elapsedText)
            listOf(R.id.island_title, R.id.island_artist, R.id.island_elapsed)
                .forEach { setTextColor(it, foreground) }
            // 进度条使用与前景一致的明暗资源，避免系统主题覆盖封面配色。
            val dark = foreground == HyperOsCardContrast.BLACK
            setProgressBar(R.id.island_progress_dark, 1000, frame.progress, false)
            setProgressBar(R.id.island_progress_light, 1000, frame.progress, false)
            setViewVisibility(R.id.island_progress_dark, if (dark) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.island_progress_light, if (dark) View.GONE else View.VISIBLE)
        }
    }

    private fun prepare(cover: Bitmap?) {
        source = cover
        album = cover
        val sample = cover?.let { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) }
            ?: Color.rgb(40, 40, 48)
        foreground = HyperOsCardContrast.foreground(sample)
        val width = HyperOsCardBackground.WIDTH
        val height = HyperOsCardBackground.HEIGHT
        val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val canvas = Canvas(small)
        canvas.drawColor(sample)
        cover?.let {
            // 先居中裁切到卡片比例，不把正方形封面压扁。
            val scale = maxOf(width.toFloat() / it.width, height.toFloat() / it.height)
            val left = (width - it.width * scale) / 2f
            val top = (height - it.height * scale) / 2f
            canvas.drawBitmap(it, null, RectF(left, top, left + it.width * scale, top + it.height * scale), paint)
        }
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = HyperOsCardBackground.blur(pixels)
        blurred.indices.forEach { blurred[it] = HyperOsCardContrast.background(blurred[it], foreground) }
        small.setPixels(blurred, 0, width, 0, 0, width, height)
        background = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawBitmap(small, null, android.graphics.Rect(0, 0, this.width, this.height), paint)
        }
        small.recycle()
    }

    fun clear() { source = null; background = null; album = null }
}
