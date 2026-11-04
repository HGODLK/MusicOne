package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicBoolean

/** 自建播放器的所有入口绑定当前实例、账号与挂载周期。 */
internal class SourcePlayerUiContextLease(
    private val original: SourcePlayerUiContext,
    private val sourceId: String,
    private val operations: SourceOperationLease,
) : SourcePlayerUiContext, AutoCloseable {
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
    override val snapshot get() = read { original.snapshot }
    override val timeline get() = read { original.timeline }
    override val form get() = read { original.form }
    override val safeDrawingInsets get() = read { original.safeDrawingInsets }
    override val transition get() = read { original.transition }
    override val actions get() = read {
        // 取出时绑定本次曲目动作，迟到回调不能悄悄切换到新歌曲的操作对象。
        val captured = original.actions
        object : SourcePlayerUiActions {
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
        }
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
