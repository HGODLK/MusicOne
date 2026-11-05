package com.musicone

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 启动动画的时间轴：光走过 M 的笔画，停顿，然后 M 放大淡出并露出首页。 */
internal object HomeLaunchTimeline {
    const val LightMillis = 700L
    const val HoldMillis = 140L
    const val ExitMillis = 410L
    const val ExitStartMillis = LightMillis + HoldMillis
    const val TotalMillis = ExitStartMillis + ExitMillis
    const val ExitScale = 1.2f

    private val LightEasing = CubicBezierEasing(.4f, 0f, .2f, 1f)
    private val ExitEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

    fun light(elapsedMillis: Long): Float =
        LightEasing.transform((elapsedMillis.toFloat() / LightMillis).coerceIn(0f, 1f))

    fun exit(elapsedMillis: Long): Float =
        ExitEasing.transform(((elapsedMillis - ExitStartMillis).toFloat() / ExitMillis).coerceIn(0f, 1f))
}

/** 启动动画每个进程只播一次；减少动态效果时不播放。 */
internal class HomeLaunchGate {
    private var prepared = false
    private var pending = false

    /** 系统启动窗口还盖在上面，光要等它撤掉才开始走。 */
    var awaitingSplash = false
        private set

    fun prepare(reduceMotion: Boolean, systemSplash: Boolean): Boolean {
        pending = !prepared && !reduceMotion
        prepared = true
        awaitingSplash = pending && systemSplash
        return pending
    }

    fun takeAnimation(): Boolean = pending.also { pending = false }

    fun splashRemoved() {
        awaitingSplash = false
    }

    companion object {
        val process = HomeLaunchGate()
    }
}

/** 启动动画的时钟。主线程卡顿时每帧最多前进一小步，动画只会暂停，不会被跳过。 */
internal class HomeLaunchClock(private val gate: HomeLaunchGate) {
    private var elapsedNanos by mutableLongStateOf(0L)
    private var waitedNanos = 0L

    val elapsedMillis: Long get() = elapsedNanos / 1_000_000L
    val finished: Boolean get() = elapsedMillis >= HomeLaunchTimeline.TotalMillis

    fun advance(frameNanos: Long) {
        val step = frameNanos.coerceIn(0L, MaxFrameNanos)
        if (gate.awaitingSplash) {
            // 一直等不到交接，说明本次没有系统窗口，直接开始。
            waitedNanos += step
            if (waitedNanos < SplashWaitNanos) return
        }
        elapsedNanos += step
    }

    private companion object {
        const val MaxFrameNanos = 34_000_000L
        const val SplashWaitNanos = 400_000_000L
    }
}

/** 绘制图标里的一笔 M；路径、位置、大小和灰色须与 launch_mark.xml 一致。 */
internal class HomeLaunchMark {
    private val path = PathParser().parsePathString(MarkPath).toPath()
    private val measure = PathMeasure().apply { setPath(path, false) }
    private val segment = Path()
    private val stroke = Stroke(width = 8.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun draw(scope: DrawScope, elapsedMillis: Long, background: Color, ink: Color) = with(scope) {
        val exit = HomeLaunchTimeline.exit(elapsedMillis)
        val visible = 1f - exit
        drawRect(background, alpha = visible)
        val unit = MarkWidth.toPx() / MarkWidthUnits * (1f + (HomeLaunchTimeline.ExitScale - 1f) * exit)
        withTransform({
            translate(center.x, center.y)
            scale(unit, unit, Offset.Zero)
            translate(-MarkCenter, -MarkCenter)
        }) {
            drawPath(path, ink, alpha = DimAlpha * visible, style = stroke)
            val light = HomeLaunchTimeline.light(elapsedMillis)
            if (light > 0f) {
                drawTrimmed(this, light, ink, visible)
                // 光的前端做成渐隐，避免一条硬边。
                if (light < 1f) HeadAlphas.forEachIndexed { index, alpha ->
                    val reach = (index + 1) * HeadStep * (light / HeadRamp).coerceAtMost(1f)
                    drawTrimmed(this, (light + reach).coerceAtMost(1f), ink, alpha * visible)
                }
            }
        }
    }

    private fun drawTrimmed(scope: DrawScope, amount: Float, ink: Color, alpha: Float) {
        segment.rewind()
        measure.getSegment(0f, measure.length * amount, segment, true)
        scope.drawPath(segment, ink, alpha = alpha, style = stroke)
    }

    private companion object {
        const val MarkPath = "M30,66 L37,43 C39,36 45,36 48,43 L54,57 L60,43 C63,36 69,36 71,43 L77,62 C80,73 65,75 62,66"
        const val MarkCenter = 54f
        const val MarkWidthUnits = 55.5f
        val MarkWidth = 120.dp
        const val DimAlpha = .3f
        const val HeadStep = .018f
        const val HeadRamp = .06f
        val HeadAlphas = floatArrayOf(.45f, .28f, .16f, .07f)
    }
}
