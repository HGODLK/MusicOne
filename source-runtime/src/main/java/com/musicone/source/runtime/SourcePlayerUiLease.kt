package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicBoolean

/** 自定义播放器的所有入口都绑定到当前音源实例、账号和页面挂载周期。 */
internal class SourcePlayerUiContextLease(
    private val original: SourcePlayerUiContext,
    private val sourceId: String,
    private val operations: SourceOperationLease,
) : SourcePlayerUiContext, SourcePlayerSharedSourceAccess, SourcePlayerLyricsMotionAccess, SourcePlayerQueueAccess,
    SourcePlayerLyricsDataAccess, SourcePlayerAudioInfoAccess, SourcePlayerBackdropAccess, AutoCloseable {
    private val mounted = AtomicBoolean(true)
    private val checkAccount = operations.deferredCheck()
    private fun checkCurrent() {
        check(mounted.get()) { "音源播放器页面已关闭" }
        checkAccount()
    }
    private fun <T> read(block: () -> T): T {
        checkCurrent()
        return block().also { checkCurrent() }
    }
    private fun dispatch(action: () -> Unit) {
        checkCurrent()
        operations.dispatch(sourceId) { checkCurrent(); action() }
    }
    private fun <T> dispatchResult(action: () -> T): T {
        checkCurrent()
        return operations.dispatch(sourceId) { checkCurrent(); action() }
    }
    override val snapshot get() = read { original.snapshot }
    override val timeline get() = read { original.timeline }
    override val form get() = read { original.form }
    override val safeDrawingInsets get() = read { original.safeDrawingInsets }
    override val transition get() = read { original.transition }
    override val actions: SourcePlayerUiActions get() = read {
        // 获取时就锁定当前曲目的操作对象，防止迟到的回调误操作到新切出来的歌。
        val captured = original.actions
        object : SourcePlayerUiActions, SourcePlayerSeekPreviewActions, SourcePlayerQueueGestureActions,
            SourcePlayerTrackMenuActions, SourcePlayerDismissGestureActions {
            override fun togglePlay() = dispatch { captured.togglePlay() }
            override fun next() = dispatch { captured.next() }
            override fun previous() = dispatch { captured.previous() }
            override fun seekTo(trackId: String, generation: Long, positionMs: Long) = dispatch {
                captured.seekTo(trackId, generation, positionMs)
            }
            override fun cyclePlaybackMode() = dispatch { captured.cyclePlaybackMode() }
            override fun selectQuality(id: String) = dispatch { captured.selectQuality(id) }
            override fun refreshQualities() = dispatch { captured.refreshQualities() }
            override fun openQueue() = dispatch { captured.openQueue() }
            override fun toggleFavorite() = dispatch { captured.toggleFavorite() }
            override fun retryLyrics() = dispatch { captured.retryLyrics() }
            // 新增能力由宿主动作对象提供，缺失时按不可用处理。
            override fun previewSeek(trackId: String, generation: Long, positionMs: Long?) = dispatch {
                (captured as? SourcePlayerSeekPreviewActions)?.previewSeek(trackId, generation, positionMs)
            }
            override fun queueDragStart(): Boolean = dispatchResult {
                (captured as? SourcePlayerQueueGestureActions)?.queueDragStart() == true
            }
            override fun queueDrag(deltaPx: Float) = dispatch {
                (captured as? SourcePlayerQueueGestureActions)?.queueDrag(deltaPx)
            }
            override fun queueDragEnd(velocityPxPerSecond: Float) = dispatch {
                (captured as? SourcePlayerQueueGestureActions)?.queueDragEnd(velocityPxPerSecond)
            }
            override fun queueDragCancel() = dispatch {
                (captured as? SourcePlayerQueueGestureActions)?.queueDragCancel()
            }
            override fun openTrackMenu(left: Float, top: Float, right: Float, bottom: Float,
                lyricOffset: Boolean): Boolean = dispatchResult {
                (captured as? SourcePlayerTrackMenuActions)?.openTrackMenu(left, top, right, bottom, lyricOffset) == true
            }
            override fun dismissDragStart(): Boolean = dispatchResult {
                (captured as? SourcePlayerDismissGestureActions)?.dismissDragStart() == true
            }
            override fun dismissDrag(deltaPx: Float) = dispatch {
                (captured as? SourcePlayerDismissGestureActions)?.dismissDrag(deltaPx)
            }
            override fun dismissDragEnd(velocityPxPerSecond: Float) = dispatch {
                (captured as? SourcePlayerDismissGestureActions)?.dismissDragEnd(velocityPxPerSecond)
            }
            override fun dismissDragCancel() = dispatch {
                (captured as? SourcePlayerDismissGestureActions)?.dismissDragCancel()
            }
        }
    }
    override fun sharedSource(element: SourcePlayerSharedElement): SourcePlayerSharedSource? =
        read { (original as? SourcePlayerSharedSourceAccess)?.sharedSource(element) }
    // 同一次挂载只包装一次，包可在组合中反复读取同一对象。
    private val lyricsLease = LyricsMotionLease()
    override val lyricsMotion: SourcePlayerLyricsMotion get() = read { lyricsLease }
    // 音源只能拿到包装层，宿主数据和背景需要逐项转发，缺失时按空数据处理。
    override val queueSnapshot: SourcePlayerQueueSnapshot get() = read {
        (original as? SourcePlayerQueueAccess)?.queueSnapshot ?: SourcePlayerQueueSnapshot(-1, 0, emptyList())
    }
    override val lyricsData: SourcePlayerLyrics get() = read {
        (original as? SourcePlayerLyricsDataAccess)?.lyricsData
            ?: SourcePlayerLyrics(original.timeline.trackId, SourcePlayerLyricsState.UNAVAILABLE, emptyList())
    }
    override val audioInfo: SourcePlayerAudioInfo? get() = read { (original as? SourcePlayerAudioInfoAccess)?.audioInfo }
    @Composable override fun Backdrop(modifier: Modifier) {
        checkCurrent()
        (original as? SourcePlayerBackdropAccess)?.Backdrop(modifier)
    }
    override fun dismiss() = dispatch { original.dismiss() }
    override fun onFailure(message: String) = dispatch { original.onFailure(message) }
    @Composable override fun Lyrics(modifier: Modifier) {
        checkCurrent()
        original.Lyrics(modifier)
    }
    @Composable override fun SharedElement(element: SourcePlayerSharedElement, modifier: Modifier,
        content: @Composable () -> Unit) {
        checkCurrent()
        original.SharedElement(element, modifier) { checkCurrent(); content() }
    }
    override fun close() { mounted.set(false) }

    /** 歌词开合对象同样绑定挂载周期与账号；宿主未提供时按关闭态返回且动作不生效。 */
    private inner class LyricsMotionLease : SourcePlayerLyricsMotion {
        private fun motion() = (original as? SourcePlayerLyricsMotionAccess)?.lyricsMotion
        override val opened get() = read { motion()?.opened == true }
        override val lyricsProgress get() = read { motion()?.lyricsProgress ?: 0f }
        override val headerProgress get() = read { motion()?.headerProgress ?: 0f }
        override val dragging get() = read { motion()?.dragging == true }
        override fun setOpened(opened: Boolean) = dispatch { motion()?.setOpened(opened) }
        override fun dragStart(): Boolean = dispatchResult { motion()?.dragStart() == true }
        override fun drag(deltaPx: Float) = dispatch { motion()?.drag(deltaPx) }
        override fun dragEnd(velocityPxPerSecond: Float) = dispatch { motion()?.dragEnd(velocityPxPerSecond) }
        override fun dragCancel() = dispatch { motion()?.dragCancel() }
        @Composable override fun LyricsPanel(style: SourcePlayerLyricsStyle, modifier: Modifier) {
            checkCurrent()
            motion()?.LyricsPanel(style, modifier)
        }
    }
}

internal fun guardSourcePlayerUi(provider: SourcePlayerUiProvider, sourceId: String,
    operations: SourceOperationLease): SourcePlayerUiProvider = object : SourcePlayerUiProvider {
    override val playerUiDeclaration get() = operations.read { provider.playerUiDeclaration }
    @Composable override fun Player(context: SourcePlayerUiContext) {
        operations.read { Unit }
        val account = operations.accountVersion()
        val guarded = remember(context, account) { SourcePlayerUiContextLease(context, sourceId, operations) }
        DisposableEffect(guarded) { onDispose { guarded.close() } }
        provider.Player(guarded)
    }
}

