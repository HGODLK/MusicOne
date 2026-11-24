package com.musicone.sourceintegration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import com.musicone.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 自建进度条的预览与提交，沿用原生进度条的歌词衔接：拖过直接接上预览位置，单击先让歌词从当前帧过渡。 */
internal class SourcePlayerSeekGesture(private val playback: MusicPlaybackRuntime, private val scope: CoroutineScope) {
    private var previewTrack by mutableStateOf<String?>(null)
    private var tapJob: Job? = null
    /** 包正在拖动进度条预览，供宿主拖动期间提帧。 */
    val previewing: Boolean get() = previewTrack != null

    fun preview(trackId: String, positionMs: Long, durationMs: Long) {
        tapJob?.cancel()
        previewTrack = trackId
        playback.seekPreview.update(positionMs.toFloat() / durationMs, durationMs)
    }

    fun endPreview() {
        if (previewTrack == null) return
        previewTrack = null
        playback.seekPreview.clear()
    }

    fun commit(trackId: String, generation: Long, fromPositionMs: Long, positionMs: Long, durationMs: Long) {
        val fraction = positionMs.toFloat() / durationMs
        // 在预览清空前封存目标，中间态歌词不能回读上一帧正式位置。
        val commit = playback.seekPreview.prepareProgressCommit(trackId, generation, positionMs)
        val dragged = previewTrack == trackId
        previewTrack = null
        tapJob?.cancel()
        if (dragged) {
            playback.seekToForTrack(trackId, fraction, commit.revision)
            return
        }
        val lyricRequest = playback.seekPreview.requestProgressLyricSeek(trackId, fromPositionMs, fraction, generation)
        tapJob = scope.launch {
            // 与原生一致：先等目标歌词就绪（最多 120ms），避免歌词构建与音频跳转解码同时竞争。
            withTimeoutOrNull(120L) { lyricRequest.targetReady.await() }
            playback.seekToForTrack(trackId, fraction, commit.revision)
        }
    }

    fun dispose() {
        tapJob?.cancel()
        tapJob = null
        endPreview()
    }
}

/** 包按页面坐标汇报朝打开方向的位移，宿主复用底部胶囊上滑拉出队列的跟手与收尾规则。 */
internal class SourcePlayerQueueDrag(private val queue: PlayerQueueState) {
    private var displacement: PlayerGestureDisplacement? = null
    val active get() = displacement != null

    fun start(): Boolean {
        cancel()
        if (!queue.beginOpeningDrag()) return false
        displacement = PlayerGestureDisplacement(original = false)
        return true
    }

    fun drag(deltaPx: Float) {
        val current = displacement ?: return
        current.move(deltaPx)
        // 队列宿主以屏幕纵向位移计量，向上为负。
        queue.dragOpeningBy(-deltaPx)
    }

    fun end(velocityPxPerSecond: Float, density: Float, blocked: Boolean) {
        val current = displacement ?: return
        displacement = null
        val open = !blocked && capsuleShouldOpenQueue(current.distancePx / density,
            -velocityPxPerSecond / density, current.reverseDistancePx / density, completed = true)
        queue.finishOpeningDrag(open, -velocityPxPerSecond)
    }

    fun cancel() {
        if (displacement == null) return
        displacement = null
        queue.finishOpeningDrag(false, 0f)
    }
}

/** 页面坐标锚点换算到根坐标；横屏画布旋转后取四角包围盒。 */
internal fun sourcePlayerAnchorInRoot(page: LayoutCoordinates?, left: Float, top: Float,
    right: Float, bottom: Float): Rect? {
    if (page == null || !page.isAttached) return null
    val corners = listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom))
        .map(page::localToRoot)
    return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}


/** 自建页面所在的宿主开合状态；每次组合更新，供下拉收起与共享源端换算读取。 */
internal data class SourcePlayerHostMotion(val motion: PageMotion, val rotation: Int, val relatedOpen: Boolean)

/** 包按页面坐标汇报朝收起方向的位移，宿主直接驱动播放页开合，松手沿用原生下拉收起判定。 */
internal class SourcePlayerDismissDrag {
    private var motion: PageMotion? = null
    // 拖动期间页面阶段不是 SHOWN，宿主据此保持包页面接收触摸，须为快照状态以触发重组。
    private var displacement by androidx.compose.runtime.mutableStateOf<PlayerGestureDisplacement?>(null)
    private var travel = 1f
    val active get() = displacement != null

    fun start(host: SourcePlayerHostMotion): Boolean {
        val target = host.motion
        if (active || target.phase != MotionPhase.SHOWN || !target.wantsOpen || !target.beginDrag()) return false
        // 竖屏行程到迷你条表面顶端；横屏画布旋转，页面纵向对应物理宽度。
        travel = (if (host.rotation == 0) (target.sources["surface"]?.bounds?.top ?: target.hostBounds.bottom) -
            target.hostBounds.top else target.hostBounds.width).coerceAtLeast(1f)
        motion = target
        displacement = PlayerGestureDisplacement(original = false)
        return true
    }

    fun drag(deltaPx: Float) {
        val current = displacement ?: return
        val target = motion ?: return
        current.move(deltaPx)
        target.dragTo(target.value - deltaPx / travel)
    }

    fun end(velocityPxPerSecond: Float, density: Float, onDismiss: () -> Unit) {
        val current = displacement ?: return
        val target = motion ?: return
        clear()
        val dismiss = shouldDismissPlayer(target.value, velocityPxPerSecond / density, true,
            current.distancePx / density, current.reverseDistancePx / density)
        target.releaseDragWithVelocity(-velocityPxPerSecond / travel, maxVelocity = 6f)
        target.request(!dismiss, rebound = !dismiss)
        if (dismiss) onDismiss()
    }

    /** 未正常抬手时只按已到达的端点收尾，与原生取消一致。 */
    fun cancel(onDismiss: () -> Unit) {
        if (displacement == null) return
        val target = motion ?: return
        clear()
        val dismiss = shouldDismissPlayer(target.value, 0f, false)
        target.releaseDragWithVelocity(0f)
        target.request(!dismiss, rebound = !dismiss)
        if (dismiss) onDismiss()
    }

    private fun clear() {
        displacement = null
        motion = null
    }
}
