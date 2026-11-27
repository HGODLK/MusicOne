package com.musicone

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * 自然过渡（AutoMix）音频引擎与界面之间的约定。
 * 引擎侧（分析、排程、混音、播放接入）只写入 AutoMixSession；界面侧（背景、封面、歌词、标题、进度条）只读取。
 * 改动这里的字段语义要两边同时改。
 */

/** “自然过渡”开关，仅保存在本机，默认关闭。 */
internal object AutoMixPreferences {
    private var store: SharedPreferences? = null
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    /** 同一开关的快照状态：杜比可用性等通过 snapshotFlow 观察的判断读这个。 */
    var enabledSnapshot by mutableStateOf(false)
        private set

    fun initialize(context: Context) {
        if (store != null) return
        store = context.applicationContext.getSharedPreferences("automix_options", Context.MODE_PRIVATE)
        mutableEnabled.value = store!!.getBoolean("enabled", false)
        enabledSnapshot = mutableEnabled.value
    }

    fun setEnabled(enabled: Boolean) {
        checkNotNull(store) { "自然过渡设置尚未初始化" }.edit().putBoolean("enabled", enabled).apply()
        mutableEnabled.value = enabled
        enabledSnapshot = enabled
    }
}

/**
 * 过渡方式，对应实验算法 vox_mix.py 的四条成功分支：变速对拍、鼓点衔接、回声退场、淡出淡入（B 前奏很轻或稀疏时）。
 * 无节拍等无法分析时不产生过渡，界面走原有切歌动画。
 */
internal enum class AutoMixKind { BEAT_MATCH, DRUM_CUT, ECHO_OUT, FADE }

/**
 * 一次自然过渡的时间表。所有 *Ms 都是“过渡时钟”：过渡音频开始输出时为 0，随实际输出推进，暂停即停。
 * 顺序保证 0 ≤ enterMs ≤ seamMs ≤ fromEndMs ≤ totalMs。
 */
internal data class AutoMixPlan(
    val from: MusicTrack,
    val to: MusicTrack,
    val kind: AutoMixKind,
    /** B 开始可闻：背景、封面渐变从这里开始。 */
    val enterMs: Long,
    /** 交接点（低频交换／鼓点衔接的下拍）：标题、进度条、通知栏、队列高亮在这里切到 B。 */
    val seamMs: Long,
    /** A 完全收干净（含回声、余音）：背景、封面渐变到这里结束。 */
    val fromEndMs: Long,
    /** 过渡结束：之后 B 以原速单独播放，会话随即结束。 */
    val totalMs: Long,
)

/** 进行中的过渡；由引擎实现，所有方法可在主线程每帧调用，必须廉价且不阻塞。 */
internal interface AutoMixTransition {
    val plan: AutoMixPlan
    fun clockMs(): Long
    /** B 自身的媒体位置（毫秒，已含切入偏移和变速），歌词和交接后的进度条跟它走；B 尚未进场时为切入点。 */
    fun toPositionMs(): Long
    /** A 自身的媒体位置（毫秒），交接前的进度条和 A 的歌词跟它走。 */
    fun fromPositionMs(): Long
    /** 视觉进度 0..1：B 在听感上所占比例（取自中高频交换的增益曲线），背景与封面直接用它，不再另加缓动。 */
    fun visualProgress(clockMs: Long): Float
}

/** 过渡结束的原因：界面据此决定收尾方式。 */
internal enum class AutoMixEnd { COMPLETED, INTERRUPTED }

internal object AutoMixSession {
    private val mutableActive = MutableStateFlow<AutoMixTransition?>(null)
    val active: StateFlow<AutoMixTransition?> = mutableActive.asStateFlow()
    private val mutableLastEnd = MutableStateFlow<Pair<AutoMixPlan, AutoMixEnd>?>(null)
    /** 最近一次结束；手动切歌、拖进度等打断时为 INTERRUPTED，界面从当前所见状态走普通切歌收尾。 */
    val lastEnd: StateFlow<Pair<AutoMixPlan, AutoMixEnd>?> = mutableLastEnd.asStateFlow()

    fun begin(transition: AutoMixTransition) { mutableActive.value = transition }

    fun end(reason: AutoMixEnd) {
        val current = mutableActive.value ?: return
        mutableActive.value = null
        mutableLastEnd.value = current.plan to reason
    }
}
