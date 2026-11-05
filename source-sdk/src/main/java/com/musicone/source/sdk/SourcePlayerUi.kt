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

data class SourcePlayerOpening(
    val durationMillis: Int,
    val barParticipates: Boolean,
    val sharedElements: Set<SourcePlayerSharedElement>,
)

enum class SourcePlayerSharedElement { ARTWORK, TITLE, ARTIST, PLAY_PAUSE }
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
