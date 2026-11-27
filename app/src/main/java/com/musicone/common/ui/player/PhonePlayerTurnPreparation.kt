package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart

internal const val PHONE_PLAYER_TURN_PREPARATION_MILLIS = 3_000L

/** 横竖屏切换的准备阶段控制器：先截一张旧画面盖在上面，等新方向布局和动画起点都稳定后再揭开。 */
@Stable
internal class PhonePlayerTurnPreparation {
    var active by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var fading by mutableStateOf(false)
        private set
    var drawingTarget by mutableStateOf(false)
        private set
    var image by mutableStateOf<ImageBitmap?>(null)
    val sourceAlpha = Animatable(1f)
    private var owner: Any? = null
    private var capture: (suspend () -> Unit)? = null
    private var captureFrame: (() -> Boolean)? = null
    private var revision by mutableIntStateOf(0)
    private var drawnRevision = -1
    private var clockGeneration = -1
    private var clockDeadlineNanos = 0L
    internal var deadlineNanos = Long.MAX_VALUE
        private set

    fun clockWaitMillis(generation: Int, nowNanos: Long = System.nanoTime()): Long =
        if (clockGeneration == generation) ((clockDeadlineNanos - nowNanos) / 1_000_000L).coerceAtLeast(0L)
        else PHONE_PLAYER_TURN_PREPARATION_MILLIS

    fun clockEntered(generation: Int) { if (clockGeneration == generation) clockGeneration = -1 }

    fun attach(owner: Any, capture: suspend () -> Unit) { this.owner = owner; this.capture = capture }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; capture = null } }
    fun targetDrawn() { if (drawingTarget) drawnRevision = revision }
    fun sourceFrameDrawn(): Boolean = captureFrame?.invoke() ?: true

    suspend fun prepare(turn: PhonePlayerTurn, target: Int, keys: Set<String>,
        contentReady: () -> Boolean = { true }, contentGeometry: () -> Any? = { Unit },
        captureContent: suspend () -> Unit = {},
        captureReading: () -> Boolean = { true },
        prepareMotion: () -> Unit = {},
        changeLayout: () -> Unit): Boolean {
        // 转向准备不设死超时，必须等新方向布局和起点帧真正就绪。
        deadlineNanos = Long.MAX_VALUE
        val interruptedFade = fading
        active = true
        preparing = true
        drawingTarget = false
        revision++
        fun placeTarget() {
            turn.begin(target, prepare = true)
            clockGeneration = if ("clock" in keys) turn.generation else -1
            clockDeadlineNanos = System.nanoTime() + PHONE_PLAYER_TURN_PREPARATION_MILLIS * 1_000_000L
            changeLayout()
            drawingTarget = true
        }
        try {
            if (image == null || interruptedFade) {
                captureFrame = capture@{
                    // 在录制旧画面快照的同一帧，同步捕获歌词阅读基线和各元素当前姿态。
                    if (!captureReading()) return@capture false
                    turn.refreshEndpoints(keys, turn.targetRotation)
                    turn.captureSourceFrame()
                    true
                }
                try {
                    coroutineScope {
                        // 并发截取旧画面快照，必须等拷贝全部完成后再切换目标布局。
                        val content = async(start = CoroutineStart.UNDISPATCHED) { captureContent() }
                        (capture ?: { awaitCancellation() }).invoke()
                        content.await()
                    }
                }
                finally { captureFrame = null }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 截取旧画面失败时放弃本次转向，保持原方向不变。
            return false
        }
        sourceAlpha.snapTo(1f)
        fading = false
        placeTarget()
        suspend fun awaitStableTarget() {
            val stable = LyricStableFrames()
            while (true) {
                withFrameNanos { }
                turn.refreshEndpoints(keys, target)
                val endpoints = turn.endpointGeometry(keys, target)
                val content = contentGeometry()
                val frame = if (drawnRevision == revision && endpoints != null && contentReady() && content != null)
                    endpoints to content else null
                if (stable.observe(frame)) return
            }
        }
        awaitStableTarget()
        // 先等目标布局稳定，再把元素补偿到动画起点并验收第二遍（此时旧画面仍盖在上面）。
        prepareMotion()
        turn.stagePreparation()
        revision++
        awaitStableTarget()
        return true
    }

    fun release() {
        if (clockGeneration >= 0)
            clockDeadlineNanos = System.nanoTime() + PHONE_PLAYER_TURN_PREPARATION_MILLIS * 1_000_000L
        captureFrame = null
        preparing = false
        active = false
        drawingTarget = false
        image = null
        deadlineNanos = Long.MAX_VALUE
    }

    suspend fun crossfade(placeFinal: suspend () -> Unit) {
        fading = true
        preparing = false
        placeFinal()
        withFrameNanos { }
        if (!ExperiencePreferences.options.reduceMotion)
            sourceAlpha.animateTo(0f, musicMotion(240))
        reset()
    }

    fun reset() {
        revision++
        clockGeneration = -1
        release()
        fading = false
    }
}
