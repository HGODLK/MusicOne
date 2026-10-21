package com.musicone.demo

import android.content.res.Resources
import android.graphics.Typeface
import android.text.TextPaint

/** 复用通用分段时钟，只将受限文字交给系统摘要模板。 */
internal class HyperOsLyricWindows(private val resources: Resources) {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private var key: WindowKey? = null
    private var plan: SystemLyricWindow? = null

    private fun width(): Float {
        val metrics = resources.displayMetrics
        val config = resources.configuration
        paint.textSize = 14f * metrics.density * config.fontScale
        return HyperOsIslandTextBudget(config.screenWidthDp, config.screenHeightDp).lyricWidthDp * metrics.density
    }

    fun summary(content: SystemIslandContent, lyric: String): HyperOsIslandSummary {
        val config = resources.configuration
        width()
        return HyperOsIslandTextBudget(config.screenWidthDp, config.screenHeightDp)
            .summary(content.title, content.artist, lyric, resources.displayMetrics.density, paint::measureText)
    }

    fun frame(snapshot: SystemPlaybackPresentation, nowMs: Long, fallback: String): SystemLyricWindowFrame {
        if (snapshot.lyrics.none { it.text.isNotBlank() }) return SystemLyricWindowFrame(HYPER_OS_NO_LYRICS)
        val width = width()
        val line = snapshot.lyricIntervalAt(nowMs)?.let {
            it.copy(text = HyperOsIslandTextBudget.singleLine(it.text))
        } ?: return SystemLyricWindowFrame(HyperOsIslandTextBudget.fit(fallback, width, paint::measureText))
        val request = WindowKey(snapshot.key, line, width, paint.textSize)
        if (request != key) {
            plan = SystemLyricWindow.create(line, width, paint::measureText)
            key = request
        }
        return requireNotNull(plan).at(snapshot.positionAt(nowMs))
    }

    private data class WindowKey(val playback: SystemPlaybackKey, val line: SystemLyricInterval,
        val width: Float, val textSize: Float)
}
