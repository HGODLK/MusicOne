package com.musicone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

/** 只负责窗口的添加、移除、位置和触摸属性；内容由 [DesktopLyricsView] 绘制。 */
@RequiresApi(31)
internal class DesktopLyricsWindow(context: Context, togglePlay: () -> Unit) {
    // 窗口上下文能给出正确的显示区域，并随旋转、分屏更新。
    private val windowContext = context.createWindowContext(
        checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val manager = windowContext.getSystemService(WindowManager::class.java)

    // Android 12 起不透明的悬浮窗会挡住下方应用的触摸，锁定穿透必须低于系统上限。
    private val passThroughAlpha = minOf(.8f,
        context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch) - .01f
    private val view = DesktopLyricsView(windowContext, ::drag, togglePlay, ::relayout)
    @SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        BASE_FLAGS, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        // 坐标统一相对整块屏幕左上角，不随系统语言方向翻转，安全区自行换算。
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "MusicOne 桌面歌词"
    }
    private var config = DesktopLyricsConfig()
    private var style: DesktopLyricStyle? = null
    private var reduceMotion = false
    private var fps = DesktopLyricsView.DESKTOP_MAX_FPS
    private var attached = false
    private var visible = false
    private val safe = Rect()

    val density: Float get() = windowContext.resources.displayMetrics.density

    fun apply(config: DesktopLyricsConfig, style: DesktopLyricStyle, reduceMotion: Boolean, fps: Int) {
        this.config = config
        this.style = style
        this.reduceMotion = reduceMotion
        this.fps = fps
        view.applyStyle(style, reduceMotion, fps)
        relayout()
        if (visible) attach()
    }

    fun present(snapshot: SystemPlaybackPresentation?) {
        if (snapshot == null) view.suspendContent() else view.present(snapshot)
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) attach() else if (attached) {
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(180L).setInterpolator(DesktopLyricsView.EASING)
                .withEndAction { if (!visible) detach() }.start()
        }
    }

    private fun attach() {
        if (style == null) return
        if (!attached) {
            measure()
            view.alpha = 0f
            try {
                manager.addView(view, params)
                attached = true
            } catch (error: RuntimeException) {
                // 权限可能刚被撤销，等下次检查再显示。
                Log.w(TAG, "桌面歌词窗口添加失败：${error.javaClass.simpleName}")
                return
            }
        }
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(220L).setInterpolator(DesktopLyricsView.EASING).start()
    }

    private fun detach() {
        if (!attached) return
        attached = false
        try { manager.removeViewImmediate(view) }
        catch (error: RuntimeException) { Log.w(TAG, "桌面歌词窗口移除失败：${error.javaClass.simpleName}") }
    }

    fun dismiss() {
        visible = false
        view.animate().cancel()
        detach()
        view.clear()
    }

    private fun relayout() {
        dragActive = false
        // 显示大小调整后按新的密度重新排版。
        style?.takeIf { it.density != density }?.let {
            val updated = it.copy(density = density)
            style = updated
            view.applyStyle(updated, reduceMotion, fps)
        }
        if (!attached) return
        measure()
        commit()
    }

    private fun commit() {
        try { manager.updateViewLayout(view, params) }
        catch (error: RuntimeException) { Log.w(TAG, "桌面歌词窗口更新失败：${error.javaClass.simpleName}") }
    }

    private fun measure() {
        val value = style ?: return
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        // 顶部放开到屏幕最上沿，可移到状态栏里；左右刘海和底部导航条仍避开。
        safe.set(insets.left, 0, metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
        params.width = (desktopLyricsWidthDp(availableDp, config.widthDp(availableDp)) * density).toInt().coerceAtMost(safe.width().coerceAtLeast(1))
        params.height = view.desiredHeight(value)
        params.x = desktopLyricsOrigin(config.fractionX, safe.left, safe.right, params.width)
        params.y = desktopLyricsOrigin(config.fractionY, safe.top, safe.bottom, params.height)
        params.flags = if (config.locked) BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else BASE_FLAGS
        params.alpha = if (config.locked) passThroughAlpha else 1f
    }

    private val availableDp: Float get() = safe.width() / density
    private var dragX = 0f
    private var dragRight = 0f
    private var dragY = 0f
    private var dragActive = false

    private fun drag(gesture: DesktopLyricsGesture, dx: Float, dy: Float, finished: Boolean) {
        if (!attached) return
        if (!dragActive) {
            dragActive = true
            dragX = params.x.toFloat()
            dragY = params.y.toFloat()
            dragRight = (params.x + params.width).toFloat()
        }
        if (gesture == DesktopLyricsGesture.MOVE) {
            dragX = (dragX + dx).coerceIn(safe.left.toFloat(), (safe.right - params.width).coerceAtLeast(safe.left).toFloat())
            dragY = (dragY + dy).coerceIn(safe.top.toFloat(), (safe.bottom - params.height).coerceAtLeast(safe.top).toFloat())
            params.x = dragX.toInt()
            params.y = dragY.toInt()
        } else {
            val (left, right) = desktopLyricsResize(gesture, dragX, dragRight, dx, safe.left.toFloat(), safe.right.toFloat(),
                minOf(DESKTOP_MIN_WIDTH_DP * density, safe.width().toFloat()))
            dragX = left
            dragRight = right
            params.x = left.toInt()
            params.width = (right.toInt() - params.x).coerceAtLeast(1)
        }
        commit()
        if (!finished) return
        dragActive = false
        DesktopLyricsOptions.update {
            val sized = if (gesture == DesktopLyricsGesture.MOVE) it else it.withWidthDp(availableDp, params.width / density)
            sized.copy(fractionX = desktopLyricsFraction(params.x, safe.left, safe.right, params.width, it.fractionX),
                fractionY = desktopLyricsFraction(params.y, safe.top, safe.bottom, params.height, it.fractionY))
        }
    }

    private companion object {
        const val TAG = "MusicOneDesktopLyrics"
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
