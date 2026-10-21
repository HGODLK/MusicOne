package com.musicone.demo

internal const val HYPER_OS_NOTIFICATION_BATCH_MS = 80L

/** 媒体与歌词按曲目配对，短时间内只提交最新完整状态；平台对象留在调用边界。 */
internal class HyperOsNotificationHandoff<N : Any, O : Any>(
    private val enqueue: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val decorate: (N, O) -> N,
    private val equivalent: (N, N) -> Boolean,
    private val publish: (N) -> Unit,
) {
    private var media: N? = null
    private var mediaTrackId: String? = null
    private var overlay: O? = null
    private var overlayTrackId: String? = null
    private var visible: N? = null
    private var pending: Runnable? = null
    private var closed = false

    fun mediaCreated(trackId: String?, value: N): N {
        media = value
        mediaTrackId = trackId
        if (closed || overlay == null) return value.also { visible = it }
        if (mediaTrackId == overlayTrackId) {
            // 同曲播控直接随 Media3 本轮通知提交，不能先发旧按钮再补一条新按钮通知。
            cancelPending()
            val current = decorate(value, requireNotNull(overlay))
            return visible?.takeIf { equivalent(it, current) } ?: current.also { visible = it }
        }
        schedule()
        // Media3 同步索取通知时继续返回完整旧卡片，不穿插普通媒体模板。
        return visible ?: value.also { visible = it }
    }

    fun mediaUpdated(trackId: String?, value: N) {
        if (closed) return
        media = value
        mediaTrackId = trackId
        if (overlay == null) emit(value) else schedule()
    }

    fun overlayUpdated(trackId: String?, value: O?) {
        if (closed) return
        overlay = value
        overlayTrackId = trackId
        if (value == null) {
            cancelPending()
            media?.let(::emit)
        } else schedule()
    }

    private fun schedule() {
        if (pending != null) return
        val task = Runnable {
            pending = null
            if (closed || mediaTrackId != overlayTrackId) return@Runnable
            val current = media ?: return@Runnable
            val content = overlay ?: return@Runnable
            emit(decorate(current, content))
        }
        pending = task
        // 合并窗口从首个事件开始，连续进度更新不会无限推迟播放按钮反馈。
        enqueue(task)
    }

    private fun emit(value: N) {
        val previous = visible
        if (previous != null && equivalent(previous, value)) return
        visible = value
        publish(value)
    }

    private fun cancelPending() {
        pending?.let(remove)
        pending = null
    }

    fun close() {
        closed = true
        cancelPending()
        media = null; overlay = null; visible = null
    }
}
