package com.musicone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/** 一次分析：A 当前条目 + 已解析的 B，返回可执行的过渡方案；不可用时返回 null。 */
internal typealias AutoMixPrepare = suspend (from: MusicTrack, fromItem: MediaItem, to: ResolvedPlayback) -> AutoMixPrepared?

/**
 * 自然过渡控制器用到的外部能力。默认值全部接真实系统（主线程、系统时钟、设置开关、离线分析、磁盘缓存），
 * 单测替换成可控的假实现，用来覆盖排程、交接、撤销这些容易回归的状态流转。
 */
@OptIn(UnstableApi::class)
internal class AutoMixControllerDeps(
    /** 单调时钟（毫秒），用于切歌后的稳定等待。 */
    val now: () -> Long = SystemClock::elapsedRealtime,
    /** 把输出端回调（播放线程）转到主线程执行。 */
    val post: (() -> Unit) -> Unit = mainThreadPoster(),
    /** 自然过渡开关。 */
    val enabled: () -> Boolean = { AutoMixPreferences.enabled.value },
    /** 创建分析器；播放服务尚未登记媒体源工厂时返回 null，本轮不分析。 */
    val preparer: (Context) -> AutoMixPrepare? = { context ->
        AutoMixEngine.mediaSourceFactory?.let { factory -> AutoMixAnalyzer(context, factory)::prepare }
    },
    /** 构造排进播放列表的 B：起初沿用 A 的身份与歌曲信息，带本次排程标记。 */
    val bItem: (context: Context, to: MusicTrack, from: MusicTrack, tag: AutoMixStartTag) -> MediaItem = { context, to, from, tag ->
        autoMixMediaItem(context, to, mediaId = from.id, metadataTrack = from, startTag = tag)
    },
    /** 读取条目上的排程标记。 */
    val tagOf: (MediaItem) -> Any? = { it.localConfiguration?.tag },
    /** 交接时把磁盘缓存的活跃 key 切到 B。 */
    val markActive: (Context, MusicTrack) -> Unit = ::markAutoMixActiveCacheKey,
    /** 过渡时钟：输出端实际写出的片段位置（毫秒）。 */
    val clipClockMs: (AutoMixAudioSink) -> Long = { it.clipClockMs() },
)

private fun mainThreadPoster(): (() -> Unit) -> Unit {
    val main = Handler(Looper.getMainLooper())
    return { block -> main.post { block() } }
}
