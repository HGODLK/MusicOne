package com.musicone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 桌面歌词画布。只在切句、逐字推进或长句滚动时请求帧；暂停、等待下一句、息屏和不可见时停帧。
 */
@RequiresApi(31)
@SuppressLint("ViewConstructor")
internal class DesktopLyricsView(
    context: Context,
    private val onDrag: (gesture: DesktopLyricsGesture, dx: Float, dy: Float, finished: Boolean) -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onConfigurationChange: () -> Unit,
) : View(context) {
    private class Layer(val block: DesktopLyricBlock, var fromOffset: Float, var toOffset: Float, var fromAlpha: Float,
        var toAlpha: Float, var startMs: Long, var moveMs: Long, var fadeMs: Long) {
        fun offset(now: Long) = lerp(fromOffset, toOffset, progress(now, moveMs))
        fun alpha(now: Long) = lerp(fromAlpha, toAlpha, progress(now, fadeMs))
        fun running(now: Long) = now - startMs < maxOf(moveMs, fadeMs)
        private fun progress(now: Long, duration: Long) =
            if (duration <= 0L) 1f else EASING.getInterpolation(((now - startMs).toFloat() / duration).coerceIn(0f, 1f))
        private fun lerp(from: Float, to: Float, value: Float) = from + (to - from) * value
    }

    private val clock = DesktopLyricClock()
    private val resolver = DesktopLyricResolver()
    private val frames = AnimationFrameSchedule()
    private var style: DesktopLyricStyle? = null
    private var reduceMotion = false
    private var fps = DESKTOP_MAX_FPS
    private var snapshot: SystemPlaybackPresentation? = null
    private var current: Layer? = null
    private var exiting: Layer? = null
    private var frameTime = SystemClock.elapsedRealtime()
    private var screenOn = true
    private var framePosted = false
    private var dragLevel = 0f
    private var dragAnimator: ValueAnimator? = null
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private val frameCallback = Choreographer.FrameCallback { nanos ->
        framePosted = false
        if (!running()) return@FrameCallback
        if (!frames.accept(nanos, fps)) requestFrame() else {
            // 把帧时间戳换算到和播放进度一致的单调时钟，避免回调延迟导致插值跳动。
            frameTime = SystemClock.elapsedRealtime() - (System.nanoTime() - nanos).coerceAtLeast(0L) / 1_000_000L
            refresh(frameTime)
            schedule()
        }
    }
    private val wake = Runnable { requestFrame() }

    fun desiredHeight(style: DesktopLyricStyle): Int = ceil(style.height + VERTICAL_PADDING_DP * 2f * style.density).toInt()

    fun applyStyle(value: DesktopLyricStyle, reduceMotion: Boolean, fps: Int) {
        this.reduceMotion = reduceMotion
        this.fps = fps.coerceAtMost(DESKTOP_MAX_FPS)
        if (style != value) {
            style = value
            rebuild()
        }
        requestFrame()
    }

    fun present(value: SystemPlaybackPresentation) {
        val now = SystemClock.elapsedRealtime()
        // 同一首歌换地址、重播的间隙保持原句，不闪回歌名。
        if (!value.readyForPresentation && snapshot?.key?.trackId == value.key.trackId && current != null) {
            clock.freeze(now)
            snapshot = snapshot?.copy(advancing = false)
            requestFrame()
            return
        }
        snapshot = value
        clock.rebase(value.key, value.positionAt(now), value.advancing, now)
        requestFrame()
    }

    /** 停止更新内容，保留最后一帧画面等窗口淡出。 */
    fun suspendContent() {
        snapshot = null
        stopFrames()
    }

    fun clear() {
        suspendContent()
        replace(null)
        exiting?.block?.release()
        exiting = null
    }

    private fun replace(layer: Layer?) {
        current?.block?.release()
        current = layer
    }

    /** 外观或尺寸变化直接换成新排版，不播放切句动画。 */
    private fun rebuild() {
        exiting?.block?.release()
        exiting = null
        val content = current?.block?.content ?: return
        replace(block(content)?.let { settled(it) })
        invalidate()
    }

    private fun settled(block: DesktopLyricBlock) = Layer(block, 0f, 0f, 1f, 1f, 0L, 0L, 0L)

    private fun block(content: DesktopLyricContent): DesktopLyricBlock? {
        val value = style ?: return null
        return if (width > 0) DesktopLyricBlock(content, value, width.toFloat()) else null
    }

    private fun refresh(now: Long) {
        val value = snapshot ?: return
        val content = resolver.resolve(value, clock.position(now), now)
        val shown = current?.block?.content
        if (shown?.key != content.key) advance(shown, content, now)
        // 同一句的翻译或注音晚到时原位更新。
        else if (shown != content) block(content)?.let { updated ->
            val motion = current
            replace(if (motion == null) settled(updated) else Layer(updated, motion.fromOffset, motion.toOffset,
                motion.fromAlpha, motion.toAlpha, motion.startMs, motion.moveMs, motion.fadeMs))
        }
        exiting?.takeIf { !it.running(now) }?.let {
            it.block.release()
            exiting = null
        }
        invalidate()
    }

    /** 最多两层：旧的退场层立即让位，当前句从现有位置和透明度接着离开。 */
    private fun advance(from: DesktopLyricContent?, to: DesktopLyricContent, now: Long) {
        val next = block(to) ?: return
        val value = style ?: return
        val transition = desktopLyricTransition(from?.key, to.key, reduceMotion)
        val scale = desktopTransitionScale(to)
        fun duration(millis: Int) = (millis * scale).toLong()
        val distance = when (transition) {
            DesktopLyricTransition.FADE -> 0f
            DesktopLyricTransition.FORWARD -> value.mainHeight / 2f
            DesktopLyricTransition.BACKWARD -> -value.mainHeight / 2f
        }
        exiting?.block?.release()
        exiting = current?.also {
            val offset = it.offset(now)
            val alpha = it.alpha(now)
            it.fromOffset = offset
            it.toOffset = if (distance == 0f) offset else -distance
            it.fromAlpha = alpha
            it.toAlpha = 0f
            it.startMs = now
            it.moveMs = duration(300)
            it.fadeMs = duration(if (reduceMotion) 120 else 220)
        }
        current = Layer(next, distance, 0f, 0f, 1f, now, duration(360), duration(if (reduceMotion) 160 else 300))
    }

    override fun onDraw(canvas: Canvas) {
        val value = style ?: return
        if (dragLevel > 0f) {
            val radius = 16f * value.density
            backgroundPaint.color = android.graphics.Color.argb((dragLevel * 84f).toInt(), 0, 0, 0)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)
        }
        // 绘制节点只能画到硬件画布；软件画布只会出现在系统截取等场景，跳过即可。
        if (!canvas.isHardwareAccelerated) return
        val position = clock.position(frameTime)
        val wordPosition = position + (snapshot?.wordLeadMs ?: 0L)
        exiting?.let { drawLayer(canvas, it, position, wordPosition, value) }
        current?.let { drawLayer(canvas, it, position, wordPosition, value) }
    }

    private fun drawLayer(canvas: Canvas, layer: Layer, position: Long, wordPosition: Long, style: DesktopLyricStyle) {
        val alpha = layer.alpha(frameTime)
        if (alpha <= 0f) return
        val save = if (alpha >= 1f) canvas.save()
            else canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        canvas.translate(0f, VERTICAL_PADDING_DP * style.density + layer.offset(frameTime))
        layer.block.draw(canvas, position, wordPosition)
        canvas.restoreToCount(save)
    }

    private fun running() = isAttachedToWindow && screenOn && windowVisibility == VISIBLE && snapshot != null && style != null

    private fun requestFrame() {
        if (framePosted || !running()) return
        framePosted = true
        removeCallbacks(wake)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun schedule() {
        removeCallbacks(wake)
        if (!running()) return
        val value = snapshot ?: return
        val now = SystemClock.elapsedRealtime()
        val position = clock.position(now)
        val wordPosition = position + value.wordLeadMs
        val layer = current
        val moving = layer?.running(now) == true || exiting != null
        if (moving || layer == null || value.advancing && layer.block.animating(position, wordPosition)) {
            requestFrame()
            return
        }
        val waits = ArrayList<Long>(3)
        resolver.loadingDeadlineMs?.let { waits += it - now }
        if (value.advancing) {
            value.lyrics.getOrNull(desktopLineIndex(value.lyrics, position) + 1)?.let { waits += it.timeMs - position }
            layer.block.nextStartMs(position, wordPosition)?.let { waits += it }
        }
        waits.minOrNull()?.let { postDelayed(wake, it.coerceIn(16L, 1_000L)) }
    }

    private fun stopFrames() {
        removeCallbacks(wake)
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        dragAnimator?.cancel()
        dragLevel = 0f
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (running()) requestFrame() else stopFrames()
    }

    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        screenOn = screenState == SCREEN_STATE_ON
        if (running()) requestFrame() else stopFrames()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            current?.block?.width = w.toFloat()
            exiting?.block?.width = w.toFloat()
            invalidate()
        }
        requestFrame()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onConfigurationChange()
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var dragging = false
    private var gesture = DesktopLyricsGesture.MOVE
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** 移动和调宽直接跟手，不加缓动；按住时显出淡底，提示窗口范围。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                startX = event.x
                dragging = false
                animateDrag(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    gesture = desktopLyricsGesture(startX, width.toFloat(), dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                } else if (dragging) {
                    lastX = event.rawX
                    lastY = event.rawY
                    onDrag(gesture, dx, dy, false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == 0 && event.pointerCount > 1) {
                // 第一根手指先抬起时换用余下的手指，窗口不跳动。
                lastX = event.getRawX(1)
                lastY = event.getRawY(1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val doubleTap = !dragging && event.actionMasked == MotionEvent.ACTION_UP && tapped(event)
                if (dragging) onDrag(gesture, 0f, 0f, true)
                dragging = false
                if (doubleTap) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    flash()
                    onDoubleTap()
                } else animateDrag(0f)
            }
        }
        return true
    }

    /** 记录一次单击；与上一次足够近时视为双击，并清掉记录避免三连击再触发。 */
    private fun tapped(event: MotionEvent): Boolean {
        if (event.eventTime - event.downTime > ViewConfiguration.getLongPressTimeout()) {
            lastTapTime = 0L
            return false
        }
        val double = lastTapTime != 0L && event.downTime - lastTapTime <= ViewConfiguration.getDoubleTapTimeout() &&
            abs(event.rawX - lastTapX) <= doubleTapSlop && abs(event.rawY - lastTapY) <= doubleTapSlop
        lastTapTime = if (double) 0L else event.eventTime
        lastTapX = event.rawX
        lastTapY = event.rawY
        return double
    }

    private fun animateDrag(target: Float) = animateDragLevel(180L, dragLevel, target)

    private fun flash() = animateDragLevel(360L, dragLevel, 1f, 0f)

    private fun animateDragLevel(millis: Long, vararg levels: Float) {
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(*levels).apply {
            duration = millis
            interpolator = EASING
            addUpdateListener { dragLevel = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    companion object {
        const val DESKTOP_MAX_FPS = 60
        private const val VERTICAL_PADDING_DP = 8f
        val EASING = PathInterpolator(.2f, 0f, 0f, 1f)
    }
}
