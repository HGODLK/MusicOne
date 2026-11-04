package com.musicone.sourceintegration

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.musicone.MusicPlaybackRuntime
import com.musicone.musicMotion
import com.musicone.source.runtime.SourceHostCallbackGuard
import com.musicone.source.sdk.*

internal class SourcePublicPageCall(val request: SourcePageRequest, val onClosed: () -> Unit,
    val guard: SourceHostCallbackGuard?) {
    val visibility = MutableTransitionState(false).apply { targetState = true }
}

/** 每次调用独立保留页面状态，关闭后恢复调用者仍挂载着的原画面。 */
internal class SourcePublicPageUiBridge(private val context: Context) : SourcePageUi, SourceRootPageUi {
    private val calls = mutableStateListOf<SourcePublicPageCall>()
    val mounted: Boolean get() = calls.isNotEmpty()
    val active: SourcePageRequest? get() = calls.lastOrNull()?.request
    var playback: MusicPlaybackRuntime? by mutableStateOf(null)

    override fun open(request: SourcePageRequest, onClosed: () -> Unit) {
        calls += SourcePublicPageCall(request, onClosed, SourceHostCallbackGuard.capture())
    }

    fun close(call: SourcePublicPageCall) {
        if (call !in calls) return
        call.visibility.targetState = false
    }

    @Composable override fun Content(request: SourcePageRequest, modifier: Modifier,
        bottomInsetDp: Float, onClose: () -> Unit) {
        val player = playback ?: return
        if (request is SourcePageRequest.Search && SourcePublicRootSearchContent(request, onClose)) return
        SourceHostUiResources(context) {
            SourcePublicPageContent(request, player, modifier, bottomInsetDp, onClose)
        }
    }

    @Composable override fun RootContent(request: SourcePageRequest, modifier: Modifier,
        bottomInsetDp: Float, onClose: () -> Unit) {
        require(isSourceRootPageRequest(request)) { "一级公共页面仅支持首页和我的" }
        val player = playback ?: return
        SourceHostUiResources(context) {
            SourcePublicPageContent(request, player, modifier, bottomInsetDp, onClose, rootPage = true)
        }
    }

    @Composable override fun ReportScroll(page: String, position: () -> SourceRootScrollPosition) {
        SourcePublicRootScrollReport(page, position)
    }

    @Composable fun Host(player: MusicPlaybackRuntime, bottomInsetDp: Float, interactive: Boolean) {
        SideEffect { playback = player }
        val accountRevision by SourceApplicationHost.get(context).accountRevision.collectAsState()
        val destination by SourceApplicationHost.get(context).destination.collectAsState()
        val installed by SourcePackageController.get(context).installed.collectAsState()
        calls.toList().forEach { call -> key(call) {
            val current = call.guard?.isCurrent() != false
            val top = call === calls.lastOrNull() && interactive && destination == null &&
                call.visibility.targetState && current
            val hostLifecycle = LocalLifecycleOwner.current.lifecycle
            val inactiveBack = remember(hostLifecycle) { object : OnBackPressedDispatcherOwner {
                override val lifecycle = hostLifecycle
                override val onBackPressedDispatcher = OnBackPressedDispatcher()
            } }
            val inheritedBack = LocalOnBackPressedDispatcherOwner.current ?: inactiveBack
            LaunchedEffect(accountRevision, installed, call.guard?.isCurrent()) {
                if (call.guard?.isCurrent() == false) close(call)
            }
            LaunchedEffect(call.visibility.isIdle, call.visibility.currentState, call.visibility.targetState) {
                if (call.visibility.isIdle && !call.visibility.currentState && !call.visibility.targetState) {
                    if (calls.remove(call) && call.guard?.isCurrent() != false) call.onClosed()
                }
            }
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides if (top) inheritedBack else inactiveBack,
                LocalSourcePublicSurface provides SourcePublicSurfaceContext(
                    active = call === calls.lastOrNull() && current, interactive = top,
                    rootOffset = androidx.compose.ui.geometry.Offset.Zero, priority = 2, separateRoot = false)) {
            AnimatedVisibility(call.visibility,
                enter = fadeIn(musicMotion(320)) + slideInHorizontally(musicMotion(360)) { it / 16 },
                exit = fadeOut(musicMotion(220)) + slideOutHorizontally(musicMotion(280)) { it / 16 }) {
            SourceHostUiResources(context) {
                Surface(Modifier.fillMaxSize().then(if (top) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                })) {
                    if (current) SourcePublicPageContent(call.request, player, Modifier.fillMaxSize(), bottomInsetDp,
                        onClose = { close(call) }, interactive = top)
                }
            }
            }
            }
        } }
    }
}

@Composable
internal fun SourcePublicPageHost(bridge: SourcePublicPageUiBridge, playback: MusicPlaybackRuntime,
    bottomInsetDp: Float, interactive: Boolean = true) = bridge.Host(playback, bottomInsetDp, interactive)
