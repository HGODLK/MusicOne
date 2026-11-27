package com.musicone

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 保存播放服务初始化时注入的 Sink、数据源和控制器实例（全局单例）。 */
@OptIn(UnstableApi::class)
internal object AutoMixEngine {
    @Volatile var sink: AutoMixAudioSink? = null
    @Volatile var mediaSourceFactory: MediaSource.Factory? = null
    /** 播放用的数据源工厂（含磁盘缓存和请求头），供离线直连解码复用。 */
    @Volatile var dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null
    @Volatile var controller: AutoMixController? = null
}

/** 自然过渡需要从播放运行时获取的能力接口，具体实现在 MusicPlaybackRuntime 里转发。 */
internal interface AutoMixHost {
    val state: MusicOneUiState
    val generation: Long
    val player: Player?
    /** 获取当前歌播完后应该自动播的下一首；单曲循环、播到队尾等情况返回 null。 */
    fun naturalNext(): MusicTrack?
    /** 获取下一首已经整首缓存好的播放信息（不消耗缓存）；本地文件直接解析返回。 */
    suspend fun preparedNext(track: MusicTrack): ResolvedPlayback?
    /** 判断当前是否允许触发自然过渡（必须正在播放，且没有在快速切歌、刷新地址或设了“播完本首停止”）。 */
    fun mayTransition(): Boolean
    /** 到达交接点时把界面和队列的当前歌曲切到下一首（播放器已经在播了，不能重新加载）。 */
    fun commitHandoff(to: ResolvedPlayback)
}

/**
 * 自然过渡主控制器：后台分析 -> 把下一首排进播放器并让 Sink 待命 -> 混音开始 -> 交接点切歌 -> 混音结束。
 * 手动切歌、拖进度或关开关时立刻打断；暂停时输出停止，过渡时钟也跟着停。
 */
@OptIn(UnstableApi::class)
internal class AutoMixController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: AutoMixHost,
    private val deps: AutoMixControllerDeps = AutoMixControllerDeps(),
) :
    AutoMixAudioSink.Listener {

    private data class Key(val generation: Long, val fromId: String, val toId: String)

    private var analyzer: AutoMixPrepare? = null
    private var analysisKey: Key? = null
    private var analysisJob: Job? = null
    private var failedKey: Key? = null
    /** 如果某对歌曲曾因错过切入点被 Sink 放弃过，记下来，后续只在片段起点之前才重新排程，防止在临界点反复排程又取消。 */
    private var strictKey: Key? = null
    /** 当前歌曲稳定播放的起始时间；切歌或拖进度后重新计时，等满 ANALYSIS_SETTLE_MS 再开始分析，避开动画卡顿。 */
    private var settleKey: Key? = null
    private var settleSince = 0L
    private var ready: Pair<Key, AutoMixPrepared>? = null
    private var armed: Pair<Key, AutoMixPrepared>? = null
    private var transition: AutoMixTransitionClock? = null
    private var handedOff = false
    /** 本次排进播放列表的下一首标记；因为上一轮过渡留下的当前歌曲也带同类标记，所以必须拿这个实例精确比对。 */
    private var bTag: AutoMixStartTag? = null
    private var armSerial = 0L
    private var ticker: Job? = null
    private var watcher: Job? = null
    /** 最近一次记过的“不触发”原因，同一首同一原因只记一次。 */
    private var skipped: Pair<Key?, String>? = null

    fun start() {
        AutoMixEngine.controller = this
        watcher = scope.launch {
            while (isActive) {
                delay(WATCH_INTERVAL_MS)
                runCatching { watch() }.onFailure { Log.w(TAG, "自然过渡巡检失败", it) }
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        interrupt("运行时释放")
        if (AutoMixEngine.controller === this) AutoMixEngine.controller = null
    }

    /** 过渡进行中（片段已开始或已待命）。 */
    val busy: Boolean get() = transition != null || armed != null

    /** 过渡期间进度条与系统进度由过渡曲线给出，交接前给 A、交接后给 B 的真实媒体位置。 */
    fun position(trackId: String?): Long? {
        val t = transition ?: return null
        return when (trackId) {
            t.plan.from.id -> if (handedOff) null else t.fromPositionMs()
            t.plan.to.id -> t.toPositionMs()
            else -> null
        }
    }

    /** 手动切歌、换音质、停止等：立即结束过渡并清掉排进去的 B。 */
    fun interrupt(reason: String) {
        val wasActive = transition != null
        disarm()
        if (wasActive) {
            Log.i(TAG, "自然过渡被打断：$reason")
            AutoMixSession.end(AutoMixEnd.INTERRUPTED)
        }
        transition = null
        ticker?.cancel()
        handedOff = false
    }

    /**
     * 拖动进度：交接前播放器可能已在 B 条目（A 很短时），交接后可能仍在 A 条目；
     * 跳到当前歌曲所在条目并移除另一首，返回 true 表示已处理。
     */
    fun seek(player: Player, positionMs: Long): Boolean {
        settleSince = deps.now()
        val pair = armed ?: return false
        val prepared = pair.second
        val wasActive = transition != null
        val targetIndex = if (handedOff) indexOf(player, isB = true) else indexOf(player, isB = false)
        if (!wasActive && !handedOff) {
            // 片段尚未开始：撤销排程按普通跳转处理，分析结果留着，跳完若仍在切入点之前会重新排程
            disarm()
            ready = pair
            return false
        }
        val wasHandedOff = handedOff
        interrupt("拖动进度")
        // 还没交接就拖动（仍在 A）：分析结果留着，拖回切入点之前会重新排程，不必重新分析
        if (!wasHandedOff) ready = pair
        if (targetIndex == C.INDEX_UNSET) return false
        player.seekTo(targetIndex, positionMs)
        removeOthers(player, targetIndex)
        Log.i(TAG, "过渡中拖动进度，留在 ${if (wasHandedOff) prepared.to.track.title else prepared.from.title}")
        return true
    }

    private fun watch() {
        val player = host.player ?: return
        val snapshot = host.state
        val current = snapshot.currentTrack ?: return
        if (transition != null) return
        val enabled = deps.enabled()
        val next = host.naturalNext()
        val key = next?.let { Key(host.generation, current.id, it.id) }
        if (key != settleKey) { settleKey = key; settleSince = deps.now() }
        armed?.let { (armedKey, _) ->
            if (!enabled || key != armedKey || !host.mayTransition() || !outputAllowed(player)) {
                Log.i(TAG, "条件变化，撤销已排程的过渡")
                disarm()
            }
            return
        }
        if (!enabled) { cancelAnalysis(); return }
        if (next == null || key == null || next.id == current.id) { cancelAnalysis(); skip(key, "没有自然接续的下一首"); return }
        if (analysisKey != null && analysisKey != key) cancelAnalysis()
        if (ready?.first != null && ready?.first != key) ready = null
        if (!host.mayTransition()) return
        // 切歌准备中（尚未 READY）不算输出链路不支持，等下一轮
        if (player.playbackState != Player.STATE_READY) return
        if (!outputAllowed(player)) { skip(key, "当前输出链路不支持（USB 独占、比特完美或 LG 原生输出）"); return }
        // 上一次过渡的 B 与 A 采样率不同：整首都在转换输出，实时格式不是 B 的真实格式，片段也接管不了
        if (AutoMixEngine.sink?.convertingB == true) { cancelAnalysis(); ready = null; skip(key, "上一次过渡后仍在转换输出格式（采样率不同），本首按普通切歌"); return }
        if (player.playbackParameters.speed != 1f) { skip(key, "倍速播放中"); return }
        if (player.currentMediaItem?.mediaId != current.id || player.mediaItemCount != 1) return
        val item = player.currentMediaItem ?: return
        val playingUrl = item.localConfiguration?.uri?.toString().orEmpty()
        if (isDsd(playingUrl)) { skip(key, "DSD 音源不支持"); return }
        if (resolvedPlaybackRequiresDolby(playingUrl) || host.state.activeQuality?.capabilityQuality == AudioQuality.DOLBY) {
            skip(key, "杜比音源不支持"); return
        }
        val readyPrepared = ready?.takeIf { it.first == key }?.second
        if (readyPrepared != null) { tryArm(player, key, readyPrepared); return }
        if (analysisKey == key || failedKey == key) return
        // 交接后画面仍在渐变（A 尚未收干净）：解码与推理会抢走渲染的 CPU，等过渡完全结束后再重新计稳定时间
        if (AutoMixSession.active.value != null) { settleSince = deps.now(); return }
        // 切歌、拖动后先让动画与新歌起播走完，再开始重活；分析约 15 秒，切入点通常在几分钟后
        if (deps.now() - settleSince < ANALYSIS_SETTLE_MS) return
        val analyzer = analyzer ?: deps.preparer(context)?.also { analyzer = it } ?: return
        analysisKey = key
        analysisJob = scope.launch {
            // 下一首尚未整首预取（或本地文件解析失败）时不算失败，下一轮再看
            val resolved = try { host.preparedNext(next) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (resolved == null) { if (analysisKey == key) analysisKey = null; skip(key, "下一首尚未整首预取"); return@launch }
            if (isDsd(resolved.source.url) || !DolbyPlaybackPolicy.permits(resolved.source) || resolved.source.requiresDolbyCapability()) {
                if (analysisKey == key) analysisKey = null
                failedKey = key; skip(key, "下一首是 DSD 或杜比音源"); return@launch
            }
            Log.i(TAG, "开始分析：${current.title} → ${next.title}")
            // A 直接用播放器正在播的条目：界面状态里的地址可能是后台比对过的其他档位（例如杜比）
            val prepared = try { analyzer(current, item, resolved) }
                catch (c: CancellationException) { throw c }
                catch (e: Exception) { Log.w(TAG, "自然过渡分析出错", e); null }
                // 内存紧张时本首按普通切歌，不能让分析拖垮播放进程
                catch (e: OutOfMemoryError) { Log.w(TAG, "自然过渡分析内存不足，本首按普通切歌", e); null }
            if (analysisKey != key) return@launch
            analysisKey = null
            if (prepared == null) failedKey = key else ready = key to prepared
        }
    }

    /** 记录一次不触发的原因（同一首同一原因只记一次），排查“不生效”时看 logcat。 */
    private fun skip(key: Key?, reason: String) {
        if (skipped == key to reason) return
        skipped = key to reason
        Log.i(TAG, "本首不做自然过渡：$reason")
    }

    private fun tryArm(player: Player, key: Key, prepared: AutoMixPrepared) {
        val sink = AutoMixEngine.sink ?: return
        val positionUs = player.currentPosition * 1000L
        val job = prepared.job
        // 片段已放完并释放（只会发生在交接之后），这份结果不能再用
        if (job.clip.size.toLong() != job.frames * 2) { ready = null; return }
        // 片段开头 aDryUntil 内仍是原样 A，播放位置在这段里也能从片段中间接入；只有离原样段结束不足 ARM_GUARD_US 才算来不及
        val lastArmUs = if (strictKey == key) job.clipStartUs - ARM_GUARD_US else job.clipStartUs + job.aDryUntilUs - ARM_GUARD_US
        if (positionUs > lastArmUs) {
            // 分析结果保留：用户拖回切入点之前（或重新从头播）时下一轮 watch 会再排程
            val reason = "当前位置已过切入点，拖回之前可重新排程"
            if (skipped != key to reason) {
                Log.i(TAG, "位置 ${positionUs / 1000}ms，片段起点 ${job.clipStartUs / 1000}ms，最晚接入 ${lastArmUs / 1000}ms")
            }
            skip(key, reason)
            return
        }
        val from = prepared.from
        val to = prepared.to.track.copy(previewUrl = prepared.to.source.url)
        // B 起初沿用 A 的身份与歌曲信息，交接点再一起改成 B，保证系统侧 mediaId 始终与当前歌曲一致
        val tag = AutoMixStartTag(prepared.bStartUs, ++armSerial)
        player.addMediaItem(deps.bItem(context, to, from, tag))
        bTag = tag
        sink.listener = this
        sink.arm(prepared.job)
        armed = key to prepared
        ready = null
        Log.i(TAG, "已排程：${from.title} → ${to.title}（${prepared.branch}），当前位置 ${positionUs / 1000}ms，" +
            "片段起点 ${job.clipStartUs / 1000}ms，原样段到 ${(job.clipStartUs + job.aDryUntilUs) / 1000}ms")
    }

    private fun disarm() {
        AutoMixEngine.sink?.disarm()
        val player = host.player
        if (armed != null && player != null && !handedOff) {
            // 移除尚未开始的 B，A 播完按原有曲末切歌
            val index = indexOf(player, isB = true)
            if (index != C.INDEX_UNSET && index != player.currentMediaItemIndex) player.removeMediaItem(index)
        }
        armed = null
        bTag = null
    }

    private fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        analysisKey = null
    }

    // —— 输出端回调（播放线程）——

    override fun onClipStarted() { deps.post(::clipStarted) }
    override fun onClipWritten() = Unit
    override fun onAborted(reason: String) { deps.post { aborted(reason) } }

    private fun clipStarted() {
        val prepared = armed?.second ?: return
        val sink = AutoMixEngine.sink ?: return
        val clock = AutoMixTransitionClock(prepared) { deps.clipClockMs(sink) }
        transition = clock
        handedOff = false
        AutoMixSession.begin(clock)
        Log.i(TAG, "过渡开始：${prepared.from.title} → ${prepared.to.track.title}（${prepared.branch}）")
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && transition === clock) {
                val now = clock.clockMs()
                if (!handedOff && now >= clock.plan.seamMs) handoff(prepared)
                if (handedOff && now >= clock.plan.totalMs) { complete(); break }
                delay(TICK_MS)
            }
        }
    }

    private fun handoff(prepared: AutoMixPrepared) {
        val player = host.player ?: return
        val to = prepared.to.track.copy(previewUrl = prepared.to.source.url)
        // 两个条目都改成 B 的身份与信息：此刻播放器可能仍在送 A 的尾巴（A 条目），也可能已进入 B 条目
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            player.replaceMediaItem(i, item.buildUpon().setMediaId(to.id).setMediaMetadata(systemMediaMetadata(to)).build())
        }
        handedOff = true
        deps.markActive(context, to)
        // 先切歌再结束：交接时会话仍为 active（界面依赖）
        host.commitHandoff(prepared.to)
        Log.i(TAG, "交接点：当前歌曲切到 ${to.title}")
    }

    private fun complete() {
        val title = armed?.second?.to?.track?.title
        val player = host.player
        transition = null
        armed = null
        ticker = null
        AutoMixSession.end(AutoMixEnd.COMPLETED)
        handedOff = false
        if (player != null) {
            val index = indexOf(player, isB = true)
            if (index != C.INDEX_UNSET && player.currentMediaItemIndex == index) removeOthers(player, index)
        }
        bTag = null
        Log.i(TAG, "过渡完成：现在播放 ${title ?: "下一首"}")
    }

    private fun aborted(reason: String) {
        if (transition == null && armed == null) return
        if (transition == null) Log.i(TAG, "输出端取消待命：$reason")
        // 片段尚未开始就因位置越过切入点被输出端放弃：分析结果留着，拖回去还能再排
        val keep = armed?.takeIf { transition == null && reason == AutoMixAudioSink.MISSED_REASON }
        interrupt(reason)
        if (keep != null) { ready = keep; strictKey = keep.first }
    }

    /**
     * B 是带本次排程标记的条目，其余都是 A。不能只看有没有起播标记：
     * 第二次过渡起当前条目就是上一次留下的 B，同样带标记，按类型判断会把 A 当成 B，撤销时移不掉新 B、完成时清不掉旧条目。
     */
    private fun indexOf(player: Player, isB: Boolean): Int {
        val tag = bTag ?: return C.INDEX_UNSET
        for (i in 0 until player.mediaItemCount) {
            val mine = deps.tagOf(player.getMediaItemAt(i)) == tag
            if (mine == isB) return i
        }
        return C.INDEX_UNSET
    }

    private fun removeOthers(player: Player, keep: Int) {
        if (keep + 1 < player.mediaItemCount) player.removeMediaItems(keep + 1, player.mediaItemCount)
        if (keep > 0) player.removeMediaItems(0, keep)
    }

    /** USB 独占、系统比特完美、LG 原生输出时不接管输出（这些链路的格式与时钟不受这里控制）。 */
    private fun outputAllowed(player: Player): Boolean {
        if (UsbAudioOutputStatus.directOutputActive) return false
        if (UsbAudioOutputStatus.current.value.route == UsbAudioOutputRoute.SYSTEM_BIT_PERFECT) return false
        if (PlaybackOutputStatus.lg.value != null) return false
        return player.playbackState == Player.STATE_READY
    }

    private fun isDsd(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".dsf") || path.endsWith(".dff") || path.endsWith(".dsd")
    }

    companion object {
        private const val TAG = "MusicOneAutoMix"
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val TICK_MS = 33L
        /** 最晚在片段原样段结束前 1.5 秒排程：覆盖输出端领先播放位置的缓冲与 B 条目的加入。 */
        private const val ARM_GUARD_US = 1_500_000L
        /** 切歌或拖动后等这么久再开始分析。 */
        private const val ANALYSIS_SETTLE_MS = 5_000L
    }
}

/** 按自动切歌规则算出下一首，单曲循环或播到队尾时返回 null。 */
internal fun autoMixNaturalNext(state: MusicOneUiState): MusicTrack? {
    val current = state.currentTrack ?: return null
    if (state.queue.isEmpty() || state.queue.none(::queueTrackSourceAvailable)) return null
    val next = nextQueuePlaybackIndex(state, automatic = true)?.let(state.queue::getOrNull) ?: return null
    return next.takeIf { it.id != current.id && queueTrackSourceAvailable(it) }
}
