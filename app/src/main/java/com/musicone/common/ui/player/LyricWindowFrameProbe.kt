package com.musicone

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi

/** 仅诊断构建启用；窗口渲染回报不等于面板实际呈现，回报丢失单独记录。 */
@RequiresApi(24)
internal class LyricWindowFrameProbe(private val view: View, private val queueVisible: () -> Boolean) {
    private var started = Long.MIN_VALUE
    private var lyrics = 0
    private var draws = 0
    private var rendered = 0
    private var dropped = 0
    private var totalDuration = 0L
    private val observer = view.viewTreeObserver
    private val onDraw = ViewTreeObserver.OnDrawListener { draws++ }
    private val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()?.window
    private var metricsListener: Window.OnFrameMetricsAvailableListener? = null

    init {
        observer.addOnDrawListener(onDraw)
        if (Build.VERSION.SDK_INT >= 24) {
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
                rendered++
                dropped += lost
                totalDuration += metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
            }
            window?.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            metricsListener = listener
        }
    }

    fun onLyricFrame(time: Long) {
        if (started == Long.MIN_VALUE) started = time
        lyrics++
        val elapsed = time - started
        if (elapsed < 2_000_000_000L) return
        val seconds = elapsed / 1_000_000_000.0
        val rate = ExperiencePreferences.options.lyricAnimationFrameRate
        Log.i("MusicLyricFrames", "驱动=${BuildConfig.MUSICONE_LYRIC_FRAME_DRIVE}，设置=${rate.label}，" +
            "屏幕模式=${view.display?.refreshRate}Hz，队列=${queueVisible()}，" +
            "歌词回调=${(lyrics / seconds).toInt()}，宿主绘制=${(draws / seconds).toInt()}，" +
            "窗口渲染回报=${(rendered / seconds).toInt()}，丢失回报=$dropped，" +
            "窗口平均耗时=${if (rendered > 0) totalDuration / rendered / 1_000_000.0 else 0.0}ms")
        started = time
        lyrics = 0; draws = 0; rendered = 0; dropped = 0; totalDuration = 0L
    }

    fun close() {
        if (observer.isAlive) observer.removeOnDrawListener(onDraw)
        if (Build.VERSION.SDK_INT >= 24) metricsListener?.let { window?.removeOnFrameMetricsAvailableListener(it) }
        metricsListener = null
    }
}
