package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 播放页可选读取的宿主数据：队列、歌词、音频规格与宿主背景。
 * 都由宿主的 context 对象按需实现，可在组合、布局、绘制阶段读取，宿主状态变化时自动触发重读。
 * 旧宿主没有对应接口时 xxxOrNull() 返回 null，包自行降级。
 */

/**
 * 播放队列只读快照，顺序即实际播放顺序（随机播放时为打乱后的顺序）。
 * currentIndex 是当前歌曲在完整队列中的位置，没有当前歌曲时为 -1；upcoming 从当前歌曲的下一首起，最多 50 首，不回绕。
 */
data class SourcePlayerQueueSnapshot(
    val currentIndex: Int,
    val size: Int,
    val upcoming: List<SourceTrackDetails>,
)

interface SourcePlayerQueueAccess {
    val queueSnapshot: SourcePlayerQueueSnapshot
}

enum class SourcePlayerLyricsState { LOADING, READY, RETRYABLE, UNAVAILABLE }

data class SourcePlayerLyricWord(val startMs: Long, val endMs: Long, val text: String)

/** transliteration 为逐词注音，格式见《歌词注音与翻译规范》。 */
data class SourcePlayerLyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<SourcePlayerLyricWord> = emptyList(),
    val transliteration: String? = null,
)

/**
 * 当前歌曲的歌词。时间已包含用户整行与逐字偏移；lineLeadMs、wordLeadMs 是音源声明的显示提前量，
 * 与宿主歌词一致的做法是用 timeline.positionMs 加上提前量后再找当前行与当前字。
 */
data class SourcePlayerLyrics(
    val trackId: String,
    val state: SourcePlayerLyricsState,
    val lines: List<SourcePlayerLyricLine>,
    val lineLeadMs: Long = 0,
    val wordLeadMs: Long = 0,
)

interface SourcePlayerLyricsDataAccess {
    val lyricsData: SourcePlayerLyrics
}

/**
 * 正在播放的音频规格，来自解码器实际报告。codec 如 FLAC、MP3；数值未知时为 0。
 * summary 是宿主音质菜单里的同款一行描述；output 是输出链路描述（如 UAC2 独占、系统输出规格），未知时为 null。
 */
data class SourcePlayerAudioInfo(
    val codec: String?,
    val sampleRate: Int,
    val bitDepth: Int,
    val bitrate: Int,
    val summary: String,
    val output: String?,
)

interface SourcePlayerAudioInfoAccess {
    /** 尚未开始解码时为 null。 */
    val audioInfo: SourcePlayerAudioInfo?
}

/** 宿主原生播放页的封面流动色场与压暗层，随当前歌曲切换；铺满 modifier 指定的区域。 */
interface SourcePlayerBackdropAccess {
    @Composable fun Backdrop(modifier: Modifier)
}

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiContext.queueSnapshotOrNull(): SourcePlayerQueueSnapshot? =
    try { (this as? SourcePlayerQueueAccess)?.queueSnapshot } catch (_: LinkageError) { null }

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiContext.lyricsDataOrNull(): SourcePlayerLyrics? =
    try { (this as? SourcePlayerLyricsDataAccess)?.lyricsData } catch (_: LinkageError) { null }

/** 外层 null 表示宿主不支持；支持但尚未解码时 audioInfo 本身为 null。 */
@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiContext.audioInfoAccessOrNull(): SourcePlayerAudioInfoAccess? =
    try { this as? SourcePlayerAudioInfoAccess } catch (_: LinkageError) { null }

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiContext.backdropOrNull(): SourcePlayerBackdropAccess? =
    try { this as? SourcePlayerBackdropAccess } catch (_: LinkageError) { null }
