package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** 主音源可选的完整播放器页面；未提供该接口时由宿主显示播放器页面。 */
interface SourcePlayerUiProviderAccess {
    val playerUiProvider: SourcePlayerUiProvider?
}

fun SourceEntry.playerUiProviderOrNull(): SourcePlayerUiProvider? =
    (this as? SourcePlayerUiProviderAccess)?.playerUiProvider ?: this as? SourcePlayerUiProvider

/** 由主音源提供完整播放器界面，宿主负责挂载页面并提供受控能力。 */
interface SourcePlayerUiProvider {
    val playerUiDeclaration: SourcePlayerUiDeclaration
    @Composable fun Player(context: SourcePlayerUiContext)
}

data class SourcePlayerUiDeclaration(
    val supportsPhone: Boolean,
    val supportsTablet: Boolean,
    val supportsPhoneLandscape: Boolean,
    val opening: SourcePlayerOpening,
)

/**
 * 开合编排。pageFade=false 时宿主不给整页加透明度，改为从迷你条表面按圆角展开、收起时收回，
 * 收起期间 transition.progress 照常从 1 走到 0；sharedOnClose=true 时共享元素收起时飞回迷你条。
 * sharedScale 未指定的元素：标题、歌手等比缩放，封面、播放键按两端包围盒拉伸。
 * artworkCornerDp 是包页面封面的圆角，过渡中从迷你条封面圆角插值到它。
 * glass=true（仅 pageFade=false 的竖屏与平板）时宿主在包页面下方画原生展开玻璃与背后压暗，
 * 迷你条整体让位给玻璃；包页面在展开前段应保持背景透明，玻璃才能透出来。
 * barCrossfade=true 时宿主为标题、歌手、播放键各画一份迷你条样式的副本，与包元素同轨飞行并交叉淡入淡出。
 */
data class SourcePlayerOpening(
    val durationMillis: Int,
    val barParticipates: Boolean,
    val sharedElements: Set<SourcePlayerSharedElement>,
    val pageFade: Boolean = true,
    val sharedOnClose: Boolean = false,
    val sharedScale: Map<SourcePlayerSharedElement, SourcePlayerSharedScale> = emptyMap(),
    val artworkCornerDp: Float = 0f,
    val glass: Boolean = false,
    val barCrossfade: Boolean = false,
) {
    // 保留旧的三参数构造，已编译的旧包仍能链接。
    constructor(durationMillis: Int, barParticipates: Boolean, sharedElements: Set<SourcePlayerSharedElement>) :
        this(durationMillis, barParticipates, sharedElements, pageFade = true)
}

enum class SourcePlayerSharedElement { ARTWORK, TITLE, ARTIST, PLAY_PAUSE }

/** STRETCH 宽高分别按两端包围盒缩放；UNIFORM 按高度比等比缩放并横向裁到两端宽度之间，文字不变形。 */
enum class SourcePlayerSharedScale { STRETCH, UNIFORM }
enum class SourcePlayerForm { PHONE_PORTRAIT, PHONE_LANDSCAPE, TABLET }

/** 已转换到页面朝向的绝对安全边距；内容按四边避让，背景仍可铺满页面。 */
data class SourcePlayerInsets(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)

data class SourcePlayerQuality(val id: String, val label: String, val compactLabel: String? = null)

data class SourcePlayerSnapshot(
    val track: SourceTrackDetails?,
    val isPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: String,
    val qualities: List<SourcePlayerQuality>,
    val activeQualityId: String?,
    val qualityLoading: Boolean,
    val qualityChanging: Boolean,
    val errorMessage: String?,
    val favorite: Boolean = false,
    /** 宿主封面 3×3 色场（ARGB，按行排列共 9 个）；封面取到色后更新，未取到时为 null。 */
    val artworkColors: List<Int>? = null,
)

/** 位置携带歌曲与播放代际；无歌曲时 trackId 为空字符串。 */
data class SourcePlayerTimeline(
    val trackId: String,
    val positionMs: Long,
    val durationMs: Long,
    val generation: Long,
    val advancing: Boolean,
)

data class SourcePlayerTransition(
    val progress: Float,
    val opening: Boolean,
    val interactive: Boolean,
    val reduceMotion: Boolean,
)

/** 仅提供状态与受宿主管理的动作，不暴露核心播放器或底部栏组件。 */
interface SourcePlayerUiContext {
    val snapshot: SourcePlayerSnapshot
    val timeline: SourcePlayerTimeline
    val form: SourcePlayerForm
    val safeDrawingInsets: SourcePlayerInsets
    val transition: SourcePlayerTransition
    val actions: SourcePlayerUiActions
    fun dismiss()
    fun onFailure(message: String)
    @Composable fun Lyrics(modifier: Modifier = Modifier)
    @Composable fun SharedElement(element: SourcePlayerSharedElement, modifier: Modifier = Modifier,
        content: @Composable () -> Unit)
}

interface SourcePlayerUiActions {
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(trackId: String, generation: Long, positionMs: Long)
    fun cyclePlaybackMode()
    fun selectQuality(id: String)
    fun refreshQualities()
    fun openQueue()
    fun toggleFavorite()
    fun retryLyrics()
}

/**
 * 拖动进度时让宿主歌词跟手预览；由宿主的 actions 对象按需实现。
 * 拖动中反复调用 previewSeek，松手调用 seekTo 提交；取消拖动传 null 结束预览。
 */
interface SourcePlayerSeekPreviewActions {
    fun previewSeek(trackId: String, generation: Long, positionMs: Long?)
}

/**
 * 跟手拉出播放队列。位移与速度均为页面坐标中朝打开方向的分量，单位 px 与 px/s；
 * 打开或回弹由宿主按原生规则判定。
 */
interface SourcePlayerQueueGestureActions {
    /** 返回 false 表示本轮不能拖动（队列已打开、菜单展开等），后续回调应停止。 */
    fun queueDragStart(): Boolean
    fun queueDrag(deltaPx: Float)
    fun queueDragEnd(velocityPxPerSecond: Float)
    fun queueDragCancel()
}

/**
 * 打开当前歌曲关联菜单；锚点为以 Player 根布局左上角为原点的页面坐标区域（px）。
 * lyricOffset=true 表示从歌词标题打开“调整歌词偏移”。返回 false 表示当前歌曲没有可用菜单。
 */
interface SourcePlayerTrackMenuActions {
    fun openTrackMenu(left: Float, top: Float, right: Float, bottom: Float, lyricOffset: Boolean): Boolean
}

// 以下获取函数为内联：包内展开后不依赖宿主中的同名方法，旧宿主缺少接口类时返回 null 以便降级。
@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiActions.seekPreviewOrNull(): SourcePlayerSeekPreviewActions? =
    try { this as? SourcePlayerSeekPreviewActions } catch (_: LinkageError) { null }

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiActions.queueGestureOrNull(): SourcePlayerQueueGestureActions? =
    try { this as? SourcePlayerQueueGestureActions } catch (_: LinkageError) { null }

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiActions.trackMenuOrNull(): SourcePlayerTrackMenuActions? =
    try { this as? SourcePlayerTrackMenuActions } catch (_: LinkageError) { null }

/** 下拉收起播放页。位移与速度取页面坐标中朝收起方向（竖屏页面向下）的分量，单位 px 与 px/s。 */
interface SourcePlayerDismissGestureActions {
    /** 返回 false 表示本轮不能拖动（菜单、队列或详情页打开等），后续回调应停止。 */
    fun dismissDragStart(): Boolean
    fun dismissDrag(deltaPx: Float)
    fun dismissDragEnd(velocityPxPerSecond: Float)
    fun dismissDragCancel()
}

/**
 * 共享元素的源端：以 Player 根布局左上角为原点的页面坐标（px）、源端圆角（px）与当前开合进度。
 * inkArgb 是迷你条该元素此刻的真实前景色（随背景自适应，封面为 null）；
 * textSizeSp 是迷你条文字字号，markSizeDp 是迷你条图标边长，未知时为 0。
 */
data class SourcePlayerSharedSource(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerPx: Float,
    val progress: Float,
    val inkArgb: Int? = null,
    val textSizeSp: Float = 0f,
    val markSizeDp: Float = 0f,
)

/** 由宿主的 context 对象按需实现；源端不可用（迷你条不在、未声明该元素）时返回 null。 */
interface SourcePlayerSharedSourceAccess {
    fun sharedSource(element: SourcePlayerSharedElement): SourcePlayerSharedSource?
}

/** STACKED 为竖屏编排（歌词与页眉两条弹簧、碰到页眉再带动）；SIDE 为横屏/双栏编排（单条弹簧，页眉直接到位）。 */
enum class SourcePlayerLyricsStyle { STACKED, SIDE }

/**
 * 宿主歌词开合编排。opened 是目标状态，lyricsProgress / headerProgress 是实际进度（0 关闭，1 打开），
 * 可在组合、布局、绘制阶段读取；包按 headerProgress 摆放自己的封面与歌曲信息。
 * 拖动位移与速度取页面坐标中朝打开方向（竖屏页面向上）的分量。
 */
interface SourcePlayerLyricsMotion {
    val opened: Boolean
    val lyricsProgress: Float
    val headerProgress: Float
    val dragging: Boolean
    fun setOpened(opened: Boolean)
    /** 返回 false 表示本轮不能拖动，后续回调应停止。 */
    fun dragStart(): Boolean
    fun drag(deltaPx: Float)
    fun dragEnd(velocityPxPerSecond: Float)
    fun dragCancel()
    /** 歌词展开后的区域；打开过程中歌词从页面底边升入，绘制会超出区域下边，控件请放在面板之后绘制。 */
    @Composable fun LyricsPanel(style: SourcePlayerLyricsStyle, modifier: Modifier = Modifier)
}

/** 由宿主的 context 对象按需实现；同一次页面挂载内返回同一个对象。 */
interface SourcePlayerLyricsMotionAccess {
    val lyricsMotion: SourcePlayerLyricsMotion
}

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiActions.dismissGestureOrNull(): SourcePlayerDismissGestureActions? =
    try { this as? SourcePlayerDismissGestureActions } catch (_: LinkageError) { null }

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiContext.sharedSourceOrNull(element: SourcePlayerSharedElement): SourcePlayerSharedSource? =
    try { (this as? SourcePlayerSharedSourceAccess)?.sharedSource(element) } catch (_: LinkageError) { null }

@Suppress("NOTHING_TO_INLINE")
inline fun SourcePlayerUiContext.lyricsMotionOrNull(): SourcePlayerLyricsMotion? =
    try { (this as? SourcePlayerLyricsMotionAccess)?.lyricsMotion } catch (_: LinkageError) { null }
