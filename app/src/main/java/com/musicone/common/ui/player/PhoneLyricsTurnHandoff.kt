package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 只在横竖形态变化时暂存整页歌词；正常播放和正反横屏互转不增加录制。 */
internal class PhoneLyricsTurnHandoff {
    internal data class Frame(val image: ImageBitmap, val pose: PhoneTurnPose, val pivot: Offset, val trackId: String)
    internal var frame by mutableStateOf<Frame?>(null)
    private var owner: Any? = null
    private var capture: (suspend () -> Unit)? = null

    fun attach(owner: Any, capture: suspend () -> Unit) { this.owner = owner; this.capture = capture }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; capture = null; clear() } }
    suspend fun capture() { capture?.invoke() }
    fun clear() { frame = null }

    fun targetAlpha(turn: PhonePlayerTurn, trackId: String): Float {
        val source = frame ?: return 1f
        if (source.trackId != trackId) return 1f
        if (turn.preparing || !turn.isTurning) return 1f
        val target = turn.basePose("lyrics") ?: return 1f
        val current = turn.currentPose("lyrics") ?: return 1f
        return phoneLyricsTurnBlend(source.pose, current, target)
    }
}

/** 混合跟随实际几何弹簧，不另开定时动画；取消或方向锁不会让内容独自前进。 */
internal fun phoneLyricsTurnBlend(source: PhoneTurnPose, current: PhoneTurnPose, target: PhoneTurnPose): Float {
    fun residual(pose: PhoneTurnPose) = phoneTurnPoseResidual(pose.copy(size = target.size), target)
    val distance = residual(source)
    val progress = if (distance <= .5f) 1f else (1f - residual(current) / distance).coerceIn(0f, 1f)
    return progress * progress * (3f - 2f * progress)
}

@Composable
internal fun Modifier.phoneLyricsTurnHandoff(handoff: PhoneLyricsTurnHandoff?, turn: PhonePlayerTurn?,
    trackId: String, top: Int = 0, bottom: Int = 0): Modifier {
    if (handoff == null || turn == null) return this
    val layer = rememberGraphicsLayer()
    val owner = remember { Any() }
    var request by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val pose = remember { arrayOfNulls<PhoneTurnPose>(1) }
    val pivot = remember { arrayOf(Offset.Zero) }
    val recordedTrack = remember { arrayOf(trackId) }
    val currentTrack by rememberUpdatedState(trackId)
    DisposableEffect(handoff, turn, owner) {
        handoff.attach(owner) {
            val pending = CompletableDeferred<Unit>()
            request = pending
            try {
                pending.await()
                val source = pose[0] ?: return@attach
                val sourcePivot = pivot[0]
                val sourceTrack = recordedTrack[0]
                val image = layer.toImageBitmap()
                currentCoroutineContext().ensureActive()
                if (currentTrack == sourceTrack)
                    handoff.frame = PhoneLyricsTurnHandoff.Frame(image, source, sourcePivot, sourceTrack)
            } finally { if (request === pending) request = null }
        }
        onDispose { request?.cancel(); handoff.detach(owner) }
    }
    fun ContentDrawScope.drawFrame() {
        drawContent()
        val source = handoff.frame ?: return
        if (source.trackId != trackId) return
        if (turn.preparing || !turn.isTurning) return
        val alpha = 1f - handoff.targetAlpha(turn, trackId)
        if (alpha <= 0f) return
        val targetPivot = turn.layoutPose("lyrics")?.pivotFraction ?: return
        // 图像与实时文字共用外层转向，只有各自的原生枢轴不同，不能套用物理屏宽高。
        drawImage(source.image, topLeft = Offset(size.width * targetPivot.x, size.height * targetPivot.y) - source.pivot,
            alpha = alpha)
    }
    return then(Modifier.drawWithContent {
        val pending = request
        if (pending != null && !pending.isCompleted) {
            recordedTrack[0] = trackId
            pose[0] = turn.currentPose("lyrics")
            val fraction = turn.layoutPose("lyrics")?.pivotFraction ?: Offset(.5f, .5f)
            pivot[0] = Offset(size.width * fraction.x, size.height * fraction.y + top)
            // 同时带走原上下延伸区，不能在交接位图边缘重新裁掉完整歌词。
            layer.record(size = IntSize(size.width.roundToInt().coerceAtLeast(1),
                (size.height.roundToInt() + top + bottom).coerceAtLeast(1))) {
                translate(top = top.toFloat()) { this@drawWithContent.drawFrame() }
            }
            translate(top = -top.toFloat()) { drawLayer(layer) }
            pending.complete(Unit)
        } else drawFrame()
    })
}
