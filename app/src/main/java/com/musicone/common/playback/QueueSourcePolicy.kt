package com.musicone

import com.musicone.sourceintegration.SourcePackageController

internal enum class QueueSourceStatus(val label: String) {
    AVAILABLE(""), INITIALIZING("音源正在初始化"), MISSING("缺少音源"),
    PENDING_REMOVAL("音源待删除"), UNAVAILABLE("音源不可用");

    fun message(source: MusicSource): String = when (this) {
        AVAILABLE -> ""
        INITIALIZING -> "${source.label}音源正在初始化"
        MISSING -> "${source.label}音源未安装，请重新导入后重启"
        PENDING_REMOVAL -> "${source.label}音源待删除，可在音源管理中撤销"
        UNAVAILABLE -> "${source.label}音源不可用，请检查音源管理后重启"
    }
}

internal fun queueSourceStatus(source: MusicSource, active: Boolean, installed: Boolean,
    initializing: Boolean = false, pendingRemoval: Boolean = false, removed: Boolean = false): QueueSourceStatus = when {
    source == MusicSource.LOCAL -> QueueSourceStatus.AVAILABLE
    pendingRemoval -> QueueSourceStatus.PENDING_REMOVAL
    removed -> QueueSourceStatus.MISSING
    active -> QueueSourceStatus.AVAILABLE
    initializing -> QueueSourceStatus.INITIALIZING
    installed -> QueueSourceStatus.UNAVAILABLE
    else -> QueueSourceStatus.MISSING
}

/** 缺包是运行状态，不覆盖曲目原有的可播资料，恢复同身份包后自然恢复。 */
internal fun queueSourceStatus(source: MusicSource): QueueSourceStatus {
    val packages = SourcePackageController.current()
    val installed = packages?.installed?.value?.firstOrNull { it.id == source.id }
    return queueSourceStatus(source, packages?.running(source.id) != null,
        installed = installed != null && !installed.removed,
        initializing = packages?.startup?.value?.initializing != false,
        pendingRemoval = installed?.pendingRemoval == true, removed = installed?.removed == true)
}

internal fun queueTrackSourceAvailable(track: MusicTrack): Boolean =
    queueSourceStatus(track.source).let { it == QueueSourceStatus.AVAILABLE || it == QueueSourceStatus.INITIALIZING }

/** 最多检查队列一轮，保留实际排序、插播优先、单曲循环和顺序播放的终点规则。 */
internal fun nextQueuePlaybackIndex(state: MusicOneUiState, automatic: Boolean,
    available: (MusicTrack) -> Boolean = ::queueTrackSourceAvailable): Int? {
    val queue = state.queue
    if (queue.isEmpty()) return null
    val current = queue.indexOfFirst { it.id == state.currentTrack?.id }.coerceAtLeast(0)
    var index = if (state.radioActive) (current + 1).takeIf { it < queue.size }
        else state.insertedNextIndex() ?: nextQueueIndex(current, queue.size, state.shuffle, state.repeatMode, automatic)
    val seen = hashSetOf<Int>()
    repeat(queue.size) {
        val candidate = index ?: return null
        if (!seen.add(candidate)) return null
        if (available(queue[candidate])) return candidate
        // 缺包项不能让单曲循环原地重试；跳过之后仍沿可见队列前进。
        index = if (state.radioActive) (candidate + 1).takeIf { it < queue.size }
            else nextQueueIndex(candidate, queue.size, state.shuffle,
                if (state.repeatMode == RepeatMode.ONE) RepeatMode.ALL else state.repeatMode, automatic)
    }
    return null
}

/** 队列播放先核对来源，再复用预加载或完整缓存；公共离线入口不套用此规则。 */
internal suspend fun <T> resolveQueuePlayback(track: MusicTrack, status: QueueSourceStatus,
    cached: () -> T?, resolve: suspend () -> T): T {
    if (status != QueueSourceStatus.AVAILABLE) throw TrackUnavailableException(status.message(track.source))
    return cached() ?: resolve()
}

internal fun MusicOneUiState.withUnavailableQueueStopped(): MusicOneUiState =
    withExhaustedPlaybackQueue("队列中暂无已启用音源的歌曲，已停止播放")
