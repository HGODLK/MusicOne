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

/** 判断歌曲对应的音源包当前是否可用；缺包只是临时运行状态，不改歌曲本身数据，装回包就能播。 */
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

/** 找下一首可播歌曲（最多扫队列一轮），遵循插播优先、单曲循环和播完停止等规则。 */
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
        // 单曲循环遇到缺包的歌不要死循环原地重试，跳过它往后找。
        index = if (state.radioActive) (candidate + 1).takeIf { it < queue.size }
            else nextQueueIndex(candidate, queue.size, state.shuffle,
                if (state.repeatMode == RepeatMode.ONE) RepeatMode.ALL else state.repeatMode, automatic)
    }
    return null
}

/** 队列播放时先确认音源包可用，再查预加载或本地缓存；离线页面入口不走这里。 */
internal suspend fun <T> resolveQueuePlayback(track: MusicTrack, status: QueueSourceStatus,
    cached: () -> T?, resolve: suspend () -> T): T {
    if (status != QueueSourceStatus.AVAILABLE) throw TrackUnavailableException(status.message(track.source))
    return cached() ?: resolve()
}

internal fun MusicOneUiState.withUnavailableQueueStopped(): MusicOneUiState =
    withExhaustedPlaybackQueue("队列中暂无已启用音源的歌曲，已停止播放")
