package com.musicone.demo

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 仅实验构建接入；HWUI 已有自身提示，不默认向同一主线程叠加会话。 */
@RequiresApi(31)
internal class WindowInteractionHints(private val window: Window) {
    private val closed = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private val thread = HandlerThread("MusicOneHintFeedback").apply { start() }
    private val handler = Handler(thread.looper)
    private val uiTid = Process.myTid()
    private val generation = AtomicLong(0L)
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var announced = false
    private val feedback = InteractionHintFeedback(onDisabled = {
        // 无能力或平台调用失败后连监听器也释放，不在后续动作中继续采样。
        Handler(Looper.getMainLooper()).post { close() }
    }) { target ->
        val manager = window.context.getSystemService(PerformanceHintManager::class.java)
        val session = manager?.createHintSession(intArrayOf(uiTid), target)
        if (!announced) {
            announced = true
            Log.i("MusicAdpf", if (session == null) "设备不支持实验会话，保持系统默认调度"
                else "歌词实验会话已创建，按实测整帧余量分配 UI 预算=$target")
        }
        session?.let {
            object : InteractionHintSession {
                override fun target(nanos: Long) = session.updateTargetWorkDuration(nanos)
                override fun report(nanos: Long) = session.reportActualWorkDuration(nanos)
                override fun close() {
                    session.close()
                    Log.i("MusicAdpf", "歌词实验会话已关闭")
                }
            }
        }
    }
    private fun frameListener(epoch: Long) = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (!closed.get() && requested.get() && generation.get() == epoch) {
            feedback.report(metrics.getMetric(FrameMetrics.DEADLINE), metrics.getMetric(FrameMetrics.TOTAL_DURATION), longArrayOf(
                metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION),
            ))
        }
    }

    /** 调用入口属于界面线程，会话创建、报告和关闭统一交给反馈线程。 */
    fun setActive(active: Boolean) {
        if (closed.get()) return
        if (requested.getAndSet(active) == active) return
        Log.i("MusicAdpf", if (active) "歌词动作采样开始" else "歌词动作采样停止")
        try {
            if (active && listener == null) {
                val current = frameListener(generation.incrementAndGet())
                window.addOnFrameMetricsAvailableListener(current, handler)
                listener = current
            } else if (!active) {
                generation.incrementAndGet()
                listener?.let { window.removeOnFrameMetricsAvailableListener(it) }
                listener = null
            }
        } catch (_: RuntimeException) { close(); return }
        handler.post {
            // 每个退出事件都必须关闭旧会话，不能被后来的动作合并吞掉。
            feedback.setActive(active && !closed.get() && requested.get())
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        requested.set(false)
        generation.incrementAndGet()
        listener?.let {
            try { window.removeOnFrameMetricsAvailableListener(it) } catch (_: RuntimeException) { }
        }
        listener = null
        handler.post { feedback.close(); thread.quitSafely() }
    }

}
