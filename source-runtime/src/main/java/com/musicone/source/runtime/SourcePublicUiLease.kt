package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicBoolean

/** 新的公共页面和组件延续包实例的操作边界，不受默认页面开关限制。 */
internal class SourcePublicUiLease(
    content: SourceContentUi,
    private val sourceId: String,
    private val operations: SourceOperationLease,
) {
    private fun checkSource(id: String) = operations.read {
        require(id == sourceId) { "不能使用其他音源的公共页面或组件上下文" }
    }

    val pages: SourcePageUi? = content.pages?.let { public ->
        val original = object : SourcePageUi {
            override fun open(request: SourcePageRequest, onClosed: () -> Unit) {
                checkSource(request.sourceId)
                operations.dispatch(sourceId) {
                    val guard = requireNotNull(SourceHostCallbackGuard.capture())
                    public.open(request) {
                        if (guard.isCurrent()) operations.dispatch(sourceId, onClosed)
                    }
                }
            }

            @Composable override fun Content(request: SourcePageRequest, modifier: Modifier,
                bottomInsetDp: Float, onClose: () -> Unit) {
                checkSource(request.sourceId)
                val mounted = remember(request) { AtomicBoolean(true) }
                DisposableEffect(mounted) { onDispose { mounted.set(false) } }
                public.Content(request, modifier, bottomInsetDp) {
                    // 嵌入页请求调用方立即退出导航，不能因已切换账号而把当前页面困住。
                    if (mounted.get()) operations.dispatch(sourceId, onClose)
                }
            }
        }
        val root = public as? SourceRootPageUi
        if (root == null) original else object : SourcePageUi by original, SourceRootPageUi {
            @Composable override fun RootContent(request: SourcePageRequest, modifier: Modifier,
                bottomInsetDp: Float, onClose: () -> Unit) {
                checkSource(request.sourceId)
                require(request is SourcePageRequest.Home || request is SourcePageRequest.My) { "一级公共页面仅支持首页和我的" }
                val mounted = remember(root, request) { AtomicBoolean(true) }
                DisposableEffect(mounted) { onDispose { mounted.set(false) } }
                root.RootContent(request, modifier, bottomInsetDp) {
                    if (mounted.get()) operations.dispatch(sourceId, onClose)
                }
            }

            @Composable override fun ReportScroll(page: String, position: () -> SourceRootScrollPosition) {
                checkSource(sourceId)
                require(page == "home" || page == "my") { "一级滚动位置仅支持首页和我的" }
                val currentPosition by rememberUpdatedState(position)
                val account = operations.accountVersion()
                val callback = remember(root, page, account) { SourceRootScrollCallback(operations.deferredCheck()) }
                DisposableEffect(callback) { onDispose { callback.close() } }
                val guardedPosition = remember(callback) { { callback.read { currentPosition() } } }
                root.ReportScroll(page, guardedPosition)
            }
        }
    }

    val components: SourceComponentUi? = content.components?.let { public -> object : SourceComponentUi {
        override fun forSource(sourceId: String, checkAccess: () -> Unit): SourceComponentScope {
            checkSource(sourceId)
            return public.forSource(sourceId) {
                checkSource(sourceId)
                checkAccess()
            }
        }
    } }
}

/** 列表观察可能晚于页面注销收尾，旧实例或旧账号不能继续向宿主页头写位置。 */
internal class SourceRootScrollCallback(private val checkActive: () -> Unit) {
    private val mounted = AtomicBoolean(true)
    fun close() { mounted.set(false) }
    private fun check() {
        if (!mounted.get()) throw kotlinx.coroutines.CancellationException("一级滚动上报已结束")
        try { checkActive() }
        catch (invalid: IllegalStateException) {
            throw kotlinx.coroutines.CancellationException("一级滚动上报租约已失效").also { it.initCause(invalid) }
        }
    }
    fun read(position: () -> SourceRootScrollPosition): SourceRootScrollPosition {
        check()
        return position().also { check() }
    }
}
